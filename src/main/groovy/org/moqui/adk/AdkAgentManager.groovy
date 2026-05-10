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
import com.google.adk.events.Event
import com.google.adk.models.Gemini
import com.google.adk.runner.Runner
import com.google.adk.sessions.InMemorySessionService
import com.google.genai.types.Content
import com.google.genai.types.Part
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Singleton holding the ADK Runner and session service, embedded in the Moqui JVM. */
class AdkAgentManager {
    static final Logger logger = LoggerFactory.getLogger(AdkAgentManager.class)

    private static volatile AdkAgentManager instance
    private final Runner runner
    private final InMemorySessionService sessionService
    private final String appName

    private AdkAgentManager(String agentName, String modelName, String apiKey, String instruction) {
        appName = agentName
        sessionService = new InMemorySessionService()
        def model = Gemini.builder().modelName(modelName).apiKey(apiKey).build()
        def agent = LlmAgent.builder()
                .name(agentName)
                .model(model)
                .instruction(instruction)
                .build()
        runner = Runner.builder()
                .agent(agent)
                .appName(agentName)
                .sessionService(sessionService)
                .build()
        logger.info("ADK initialized: agent={}, model={}", agentName, modelName)
    }

    static synchronized AdkAgentManager getInstance(String agentName, String modelName,
                                                     String apiKey, String instruction) {
        if (instance == null)
            instance = new AdkAgentManager(agentName, modelName, apiKey, instruction)
        return instance
    }

    static synchronized void reset() { instance = null }

    String createSession(String userId) {
        def session = sessionService.createSession(appName, userId, Optional.empty(), [:]).blockingGet()
        return session.id()
    }

    String runSync(String userId, String sessionId, String messageText) {
        Content content = Content.builder()
                .role("user")
                .parts([Part.fromText(messageText)])
                .build()
        List<Event> events = runner.runAsync(userId, sessionId, content).toList().blockingGet()
        for (int i = events.size() - 1; i >= 0; i--) {
            Event ev = events[i]
            if (ev.finalResponse()) {
                String text = ev.stringifyContent()
                if (text) return text
            }
        }
        return ""
    }
}
