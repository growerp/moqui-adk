/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.adk

import com.google.adk.agents.LlmAgent
import com.google.adk.agents.RunConfig
import com.google.adk.events.Event
import com.google.adk.runner.InMemoryRunner
import com.google.genai.types.Content
import com.google.genai.types.Part
import io.reactivex.rxjava3.core.Flowable
import org.moqui.context.ExecutionContextFactory

/**
 * Singleton facade over the Google ADK Java SDK (InMemoryRunner).
 *
 * Call init() after loading config from the DB or environment.
 * All session state lives in memory and is lost on Moqui restart.
 */
class AdkManager {

    static final String APP_NAME = 'moqui-adk'

    private static volatile LlmAgent      agent
    private static volatile InMemoryRunner runner
    static  volatile Map<String, Object>  currentConfig = [:]

    static void init(String agentName, String modelName, String instruction, String apiKey) {
        if (apiKey) System.setProperty('GOOGLE_API_KEY', apiKey)

        // If no custom agent is configured, use the built-in HelloTimeAgent example
        if (!agentName) {
            agent  = (LlmAgent) HelloTimeAgent.ROOT_AGENT
        } else {
            agent = LlmAgent.builder()
                    .name(agentName)
                    .model(modelName ?: 'gemini-2.0-flash')
                    .instruction(instruction ?: '')
                    .build()
        }

        // InMemoryRunner creates its own InMemorySessionService internally
        runner = new InMemoryRunner(agent, APP_NAME)
        currentConfig = [agentName: agent.name(), modelName: modelName]
    }

    static boolean isInitialized() { runner != null }

    /**
     * Idempotent lazy init callable from the servlet (no Moqui service context needed).
     * Reads DB config first, falls back to env vars, then defaults to HelloTimeAgent.
     */
    static void lazyInit(ExecutionContextFactory ecf) {
        if (isInitialized()) return
        // Try DB config
        def cfg = null
        try {
            def ec = ecf.getExecutionContext()
            cfg = ec.entity.find('moqui.adk.AdkAgentConfig').condition('enabled', 'Y').one()
        } catch (Exception ignored) {}
        if (cfg) {
            init(cfg.agentName as String, cfg.modelName as String,
                 cfg.instruction as String, cfg.apiKey as String)
            return
        }
        // Use env key if present; init anyway so runner/sessions work (key needed only at run time)
        String envKey = System.getenv('GOOGLE_API_KEY') ?:
                        System.getenv('GOOGLE_GENAI_API_KEY') ?:
                        System.getenv('GEMINI_API_KEY') ?: ''
        init(null, 'gemini-2.0-flash', '', envKey)
    }

    static List<String> listAgents() {
        LlmAgent a = isInitialized() ? agent : (LlmAgent) HelloTimeAgent.ROOT_AGENT
        [a.name()]
    }

    // ── Session management ────────────────────────────────────────────────────

    static Map createSession(String userId) {
        // createSession(appName, userId, initialState, sessionId) — null = auto-generate id
        def session = runner.sessionService()
                .createSession(APP_NAME, userId, null, null)
                .blockingGet()
        [id           : session.id(),
         appName      : APP_NAME,
         userId       : session.userId(),
         state        : session.state() ?: [:],
         events       : [],
         lastUpdateTime: System.currentTimeMillis()]
    }

    static Map getSession(String userId, String sessionId) {
        def sessionOpt = runner.sessionService()
                .getSession(APP_NAME, userId, sessionId, Optional.empty())
                .blockingGet()
        if (!sessionOpt) return null
        [id           : sessionOpt.id(),
         appName      : APP_NAME,
         userId       : sessionOpt.userId(),
         state        : sessionOpt.state() ?: [:],
         events       : [],
         lastUpdateTime: System.currentTimeMillis()]
    }

    static List<Map> listSessions(String userId) {
        def response = runner.sessionService().listSessions(APP_NAME, userId).blockingGet()
        response.sessions().collect { s ->
            [id: s.id(), appName: APP_NAME, userId: s.userId()]
        }
    }

    static void deleteSession(String userId, String sessionId) {
        runner.sessionService().deleteSession(APP_NAME, userId, sessionId).blockingAwait()
    }

    // ── Agent execution ───────────────────────────────────────────────────────

    static RunConfig defaultRunConfig() {
        RunConfig.builder().build()
    }

    /** Synchronous run — blocks until all events are produced. */
    static List<Map> runAgent(String userId, String sessionId, String text) {
        Content userContent = buildUserContent(text)
        List<Map> events = []
        Flowable<Event> flow = runner.runAsync(userId, sessionId, userContent, defaultRunConfig())
        flow.blockingSubscribe { Event e -> events << eventToMap(e) }
        events
    }

    /**
     * Streaming run — calls eventCallback for each event, then
     * doneCallback(null) on completion or doneCallback(throwable) on error.
     */
    static void runAgentSse(String userId, String sessionId, String text,
                            Closure eventCallback, Closure doneCallback) {
        Content userContent = buildUserContent(text)
        Flowable<Event> flow = runner.runAsync(userId, sessionId, userContent, defaultRunConfig())
        flow.subscribe(
            { Event e -> eventCallback(eventToMap(e)) },
            { Throwable t -> doneCallback(t) },
            { doneCallback(null) }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Content buildUserContent(String text) {
        Content.builder()
               .role('user')
               .parts([Part.fromText(text)])
               .build()
    }

    static Map eventToMap(Event e) {
        Map m = [id: e.id(), invocationId: e.invocationId(), author: e.author()]

        Optional<Content> contentOpt = e.content()
        if (contentOpt.isPresent()) {
            Content c = contentOpt.get()
            m.content = [
                role : c.role().isPresent() ? c.role().get() : '',
                parts: c.parts().isPresent() ? c.parts().get().collect { Part p ->
                    p.text().isPresent() ? [text: p.text().get()] : [:]
                } : []
            ]
        }

        Optional<Boolean> partialOpt = e.partial()
        if (partialOpt.isPresent()) m.partial = partialOpt.get()

        m
    }
}
