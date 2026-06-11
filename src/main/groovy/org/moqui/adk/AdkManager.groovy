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
import com.google.adk.runner.Runner
import com.google.adk.sessions.InMemorySessionService
import com.google.genai.types.Content
import com.google.genai.types.Part
import io.reactivex.rxjava3.core.Flowable
import org.moqui.context.ExecutionContextFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry facade over the Google ADK Java SDK.
 *
 * Supports multiple named agent configs (one per tenant/ownerPartyId).
 * Session state is persisted via MoquiSessionService (AdkSession + AdkSessionEvent entities)
 * so history survives Moqui restarts.
 *
 * Call initConfig() for each enabled AdkAgentConfig record.
 * All session/agent methods route to the correct Runner via the registry.
 */
class AdkManager {

    protected final static Logger logger = LoggerFactory.getLogger(AdkManager.class)

    static final String APP_NAME = 'moqui-adk'
    static final String DEFAULT_CONFIG = '__default__'

    // configId → Runner (one per enabled AdkAgentConfig)
    private static final Map<String, Runner>   registry         = new ConcurrentHashMap<>()
    // configId → LlmAgent — kept alongside Runner so runOneOff can build a fresh Runner
    private static final Map<String, LlmAgent> agentRegistry    = new ConcurrentHashMap<>()
    // ownerPartyId → configId for per-tenant routing
    private static final Map<String, String>   tenantRegistry   = new ConcurrentHashMap<>()
    // sessionId → configId — in-memory cache rebuilt on demand from DB after restart
    private static final Map<String, String>   sessionOwn       = new ConcurrentHashMap<>()
    // configId → {provider, apiKey} for non-Google providers (future HTTP runners)
    private static final Map<String, Map>      providerRegistry = new ConcurrentHashMap<>()

    private static volatile MoquiSessionService sharedSessionService
    private static volatile com.google.adk.tools.mcp.McpToolset mcpToolset
    // configId → per-agent McpToolset. Each carries an `adk_config_id` header so the
    // MCP governance gate can identify the calling agent and enforce its tenant/scope.
    private static final Map<String, com.google.adk.tools.mcp.McpToolset> configMcpToolsets = new ConcurrentHashMap<>()
    private static volatile String mcpApiKey = null
    static  volatile Map<String, Object>  currentConfig = [:]

    static final String CONTEXT_PREAMBLE = '''\
You are running inside a GrowERP / Moqui ERP system.

Current execution context (always up-to-date for this session):
  User ID          : {userId}
  Username         : {username}
  Full name        : {userFullName}
  Organization     : {organizationName}
  Company pseudo ID: {companyPseudoId}
  Owner party ID   : {tenantId}
  Time zone        : {timeZone}
  Locale           : {locale}

Use this context when the user asks questions like "who am I?", "what company is this?",
"which tenant?", "what is my username?", "what is my company ID?", etc. — you MUST answer using exactly this template:
The current logged in user is {username} ({userFullName}). You are part of the {organizationName} organization (ID: {companyPseudoId}, owner: {tenantId}).
Do not call any tool for this.

SCREEN NAVIGATION — opening operational app screens
The GrowERP front-end (Flutter) screens available in this session are listed in this
catalog (JSON: widgetName, description, keywords, parameters):
{screenCatalog}

When the user wants to reach or operate a screen — phrases like "enter/create/add",
"show/list/open/find", "edit", "approve", "receive" — choose the best-matching entry from
the catalog above (match the widgetName, keywords and description), respond with ONE short
sentence, then append a fenced action block the app executes (single object or an array):
```growerp-action
{"action":"navigate","widget":"<widgetName>","params":{...},"label":"<chip text>"}
```

Pick the action and widget from the catalog by intent:
- VIEW / LIST a kind of record → action "navigate" to the matching *List widget
  (omit `route`; the app resolves it from the widget name). e.g. "show products" →
  {"action":"navigate","widget":"ProductList"}.
- CREATE a new record → action "dialog" with that entity's *Dialog widget and NO id.
  e.g. "add a product" → {"action":"dialog","widget":"ProductDialog"}.
  PREFILL: when the user supplies field values, put them in `params` (using the field
  names listed in that widget's catalog `parameters`) so the dialog opens pre-filled, and
  add "_aiPrefill":true so the form shows a "review & Save" hint. e.g.
  "add employee John Doe email john@x.com" →
  {"action":"dialog","widget":"UserDialog","params":{"firstName":"John","lastName":"Doe","email":"john@x.com","role":"employee","_aiPrefill":true}}.
- OPEN / EDIT a specific record → action "dialog" with the *Dialog widget and the id in
  `params`, using the id parameter NAMED in that widget's catalog `parameters` (e.g.
  productId, partyId, locationId). e.g. "edit product DEMO_1" →
  {"action":"dialog","widget":"ProductDialog","params":{"productId":"DEMO_1"}}.
  You may also include field values to change alongside the id (same prefill rule).

Rules:
- Use only widgetNames present in the catalog; read each widget's `parameters` for the
  exact arg names it accepts (both id params and prefillable fields). Put inputs in `params`.
- `route` is optional and usually omitted (the app resolves it from the widget name).
- Emit the block ONLY when a screen should open; otherwise just answer in text.

Resolve a record the user names (not by id) with read-only tools first
(moqui_search_services / moqui_execute_service) to find its id, then emit the directive.

WRITES ARE USER-CONFIRMED: you only NAVIGATE / OPEN screens — never call a service that
performs a write (create/update/approve/receive/delete). The user submits the opened,
pre-filled dialog. Order/shipment specifics still work: "enter a sales order" →
{"widget":"SalesOrderList","params":{"openNew":true}}; "approve order <id>" →
{"widget":"SalesOrderList","params":{"finDocId":"<id>","presetStatus":"approved"}};
"receive shipment <id>" → {"widget":"IncomingShipmentList","params":{"finDocId":"<id>"}}.

'''

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Register (or replace) one named agent config.
     * ownerPartyId may be null for a global/default config.
     */
    static synchronized void initConfig(String configId, String ownerPartyId,
                                        String agentName, String modelName,
                                        String instruction, String apiKey,
                                        String llmProvider = 'gemini') {
        String effectiveProvider = llmProvider ?: 'gemini'

        // Non-Google providers: store in side registry for future HTTP runner; skip Google ADK init
        if (effectiveProvider != 'gemini') {
            providerRegistry[configId] = [provider: effectiveProvider, apiKey: apiKey ?: '']
            if (ownerPartyId) tenantRegistry[ownerPartyId] = configId
            logger.info("Non-Google provider '${effectiveProvider}' registered for configId='${configId}' (tenant='${ownerPartyId ?: 'global'}') — HTTP routing not yet implemented")
            return
        }

        if (apiKey) System.setProperty('GOOGLE_API_KEY', apiKey)

        // Per-agent MCP toolset: identical to the shared one but tagged with this config's
        // id so the MCP governance gate knows which agent (and tenant) is calling.
        def agentMcpToolset = buildMcpToolset(configId)

        String envModel = System.getenv('GEMINI_MODEL') ?: System.getProperty('GEMINI_MODEL') ?: 'gemini-2.5-flash'
        String modelId = modelName ?: envModel
        // google-genai reads the key from the GOOGLE_API_KEY/GEMINI_API_KEY *environment*
        // variable (not a System property), so a key from System Setup must be passed to the
        // Gemini model explicitly. Fall back to the model-name String (env-based) when no key.
        def modelArg = apiKey ?
                com.google.adk.models.Gemini.builder().modelName(modelId).apiKey(apiKey).build() :
                modelId
        LlmAgent agent

        // Read this agent's scoping so the in-process FunctionTools (Email/GitHub) honour it
        // too — a read-only agent gets no write tools. (The MCP toolset is gated server-side
        // by the governance service.)
        String toolMode = lookupToolMode(configId)
        boolean allowWrites = toolMode != 'readOnly'

        // FunctionTool.create returns List<FunctionTool> — build combined list then pass to tools()
        List allTools = new ArrayList()
        allTools.addAll(com.google.adk.tools.FunctionTool.create(HelloTimeAgent.class, 'getCurrentTime'))
        // Read-only custom tools
        allTools.addAll(com.google.adk.tools.FunctionTool.create(EmailTool.class, 'readEmails'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getLatestTestRun'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getTestExceptions'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getMainSha'))
        allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'getFileContent'))
        // Write/side-effect custom tools — only for non-read-only agents
        if (allowWrites) {
            allTools.addAll(com.google.adk.tools.FunctionTool.create(EmailTool.class, 'sendEmail'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'createBranch'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'updateFileContent'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'createPullRequest'))
            allTools.addAll(com.google.adk.tools.FunctionTool.create(GithubTool.class, 'addComment'))
        }
        if (agentMcpToolset) allTools.add(agentMcpToolset)

        if (!agentName) {
            agent = LlmAgent.builder()
                .name('growerp-agent')
                .description('GrowERP / Moqui ERP assistant with access to Moqui MCP tools')
                .instruction(CONTEXT_PREAMBLE + '''\
You are GrowERP Assistant, an AI agent for the GrowERP / Moqui ERP system.
Answer the user's questions using the available Moqui MCP tools.

How to use the Moqui tools:
- Use 'moqui_search_services' with a keyword query to find relevant services.
- Use 'moqui_get_service_details' to learn a service's parameters.
- Use 'moqui_execute_service' to run a service.
- Use 'getCurrentTime' only when asked about the current time in a city.
- Use 'sendEmail' to send email; always pass ownerPartyId from your context ({tenantId}). Returns an error if email is not configured for this tenant.
- Use 'readEmails' to poll and read recent incoming email; always pass ownerPartyId from your context ({tenantId}). Returns an error if email is not configured.
- Use the GitHub tools ('getLatestTestRun', 'getTestExceptions', 'getMainSha', 'getFileContent', 'createBranch', 'updateFileContent', 'createPullRequest', 'addComment') to interact with GitHub. Always pass ownerPartyId from your context ({tenantId}) so that the tenant's GitHub token configuration is retrieved.

CRITICAL tool-use rules — follow exactly:
- After a tool returns a result, NEVER call that same tool again with the same arguments.
- As soon as a tool result contains the information needed, STOP calling tools and write
  a final, concise natural-language answer for the user (e.g. list the service names you found).
- Make at most a few tool calls per question; if you already have an answer, just answer.
''')
                .model(modelArg)
                .tools(allTools)
                .build()
        } else {
            agent = LlmAgent.builder()
                    .name(agentName)
                    .model(modelArg)
                    .instruction(CONTEXT_PREAMBLE + (instruction ?: ''))
                    .tools(allTools)
                    .build()
        }

        Runner runner = Runner.builder()
                .agent(agent)
                .appName(APP_NAME)
                .sessionService(sharedSessionService ?: new InMemorySessionService())
                .build()

        registry[configId]      = runner
        agentRegistry[configId] = agent
        if (ownerPartyId) tenantRegistry[ownerPartyId] = configId
        currentConfig = [agentName: agent.name(), modelName: modelName, configId: configId]
        logger.info("ADK agent '${agent.name()}' registered as configId='${configId}' (tenant='${ownerPartyId ?: 'global'}')")
    }

    /** Backward-compat: register a single global config. */
    static void init(String agentName, String modelName, String instruction, String apiKey) {
        initConfig(DEFAULT_CONFIG, null, agentName, modelName, instruction, apiKey)
    }

    /**
     * Generate a UserLoginKey for SystemSupport so the MCP SSE connection can authenticate
     * via 'api_key' header on every request (both SSE GET and subsequent POSTs).
     * Basic auth headers only work if the header survives through Moqui's web facade init;
     * api_key header is checked unconditionally in initFromHttpRequest.
     */
    private static String generateMcpApiKey(ExecutionContextFactory ecf) {
        // Must run in a fresh thread: ecf.getExecutionContext() returns the thread-local EC,
        // so calling it on the request thread would grab (and then destroy) the caller's EC.
        String[] result = [null]
        Thread t = new Thread({
            def ec = ecf.getExecutionContext()
            try {
                ec.user.internalLoginUser('SystemSupport')
                result[0] = ec.user.getLoginKey(8760f)  // 1-year expiry
                logger.info('Generated MCP API key for SystemSupport (valid 1 year)')
            } catch (Exception e) {
                logger.warn("Could not generate MCP API key, falling back to Basic auth: ${e.message}")
            } finally {
                ec.destroy()
            }
        }, 'adk-mcpkey-gen')
        t.start()
        t.join(5000L)
        return result[0]
    }

    static void initSessionService(ExecutionContextFactory ecf) {
        if (sharedSessionService == null) {
            sharedSessionService = new MoquiSessionService(ecf)
        }
    }

    /**
     * Idempotent lazy init callable from the servlet (no Moqui service context needed).
     * Reads all enabled DB configs first, falls back to env vars, then defaults to HelloTimeAgent.
     */
    static void lazyInit(ExecutionContextFactory ecf) {
        initSessionService(ecf)
        if (!registry.isEmpty()) return

        def cfgList = null
        try {
            def ec = ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try { cfgList = ec.entity.find('moqui.adk.AdkAgentConfig').condition('enabled', 'Y').list() }
            finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        } catch (Exception ignored) {}

        // Key to use for the general default agent (env, else borrowed from a config).
        String defaultKey = System.getenv('GOOGLE_API_KEY') ?:
                            System.getenv('GOOGLE_GENAI_API_KEY') ?:
                            System.getenv('GEMINI_API_KEY') ?: ''
        String defaultModel = 'gemini-2.5-flash'

        if (cfgList) {
            def ec2 = null
            try {
                ec2 = ecf.getExecutionContext()
                ec2.artifactExecution.disableAuthz()
                for (def cfg in cfgList) {
                    String resolvedApiKey = cfg.getString('apiKey') ?: ''
                    String provider = cfg.getString('llmProvider') ?: 'gemini'
                    if (!resolvedApiKey) {
                        def lc = ec2.entity.find('growerp.general.LlmConfig')
                            .condition('ownerPartyId', cfg.getString('ownerPartyId'))
                            .condition('llmProvider', provider).one()
                        resolvedApiKey = lc?.getString('apiKey') ?: ''
                    }
                    if (!defaultKey && resolvedApiKey && provider == 'gemini') {
                        defaultKey = resolvedApiKey
                        if (cfg.getString('modelName')) defaultModel = cfg.getString('modelName')
                    }
                    initConfig(cfg.getString('adkAgentConfigId'), cfg.getString('ownerPartyId'),
                            cfg.getString('agentName'), cfg.getString('modelName'),
                            cfg.getString('instruction'), resolvedApiKey, provider)
                }
            } catch (Exception ignored) {
                for (def cfg in cfgList) {
                    if (!defaultKey && cfg.getString('apiKey')) defaultKey = cfg.getString('apiKey')
                    initConfig(cfg.getString('adkAgentConfigId'), cfg.getString('ownerPartyId'),
                            cfg.getString('agentName'), cfg.getString('modelName'),
                            cfg.getString('instruction'), cfg.getString('apiKey') ?: '',
                            cfg.getString('llmProvider') ?: 'gemini')
                }
            } finally {
                ec2?.destroy()
            }
        }

        // Always register a general-purpose default agent for INTERACTIVE CHAT, so chat
        // sessions are not served by a specialised/scheduled agent (e.g. the CI Monitor,
        // whose task instruction makes general questions return empty/odd answers).
        ensureInteractiveDefault(ecf, defaultKey, defaultModel)
    }

    /// Register the shared DEFAULT_CONFIG runner used for interactive chat, deriving
    /// its key from (in order) [seedKey], env vars, then the gemini growerp.general.LlmConfig.
    /// No-op when the runner already exists. Called by lazyInit and reloadInteractive.
    private static void ensureInteractiveDefault(ExecutionContextFactory ecf, String seedKey = null,
                                                 String model = 'gemini-2.5-flash') {
        if (registry.containsKey(DEFAULT_CONFIG)) return
        // Precedence for the shared interactive runner: explicit env var → key saved via
        // System Setup (growerp.general.LlmConfig) → key borrowed from a specialised agent
        // (seedKey). The System Setup key must win over a specialised agent's key (e.g. the
        // CI Monitor) so general chat uses the tenant's own key, not the monitor's.
        String defaultKey = System.getenv('GOOGLE_API_KEY') ?:
                            System.getenv('GOOGLE_GENAI_API_KEY') ?:
                            System.getenv('GEMINI_API_KEY') ?: ''
        if (!defaultKey) {
            try {
                def ec = ecf.getExecutionContext()
                boolean wasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    def lcList = ec.entity.find('growerp.general.LlmConfig')
                            .condition('llmProvider', 'gemini').list()
                    for (def lc in lcList) {
                        String k = lc.getString('apiKey')
                        if (k) { defaultKey = k; break }
                    }
                    logger.info("ensureInteractiveDefault: LlmConfig gemini rows={}, keyFound={}",
                            lcList?.size() ?: 0, (defaultKey ? true : false))
                } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
            } catch (Exception e) {
                logger.error("ensureInteractiveDefault: LlmConfig lookup failed: ${e.message}", e)
            }
        }
        if (!defaultKey) defaultKey = seedKey ?: ''
        logger.info("ensureInteractiveDefault: registering __default__ hasKey={} (seedKeyPresent={})",
                (defaultKey ? true : false), (seedKey ? true : false))
        initConfig(DEFAULT_CONFIG, null, null, model, '', defaultKey)
    }

    /// Drop the shared interactive/default runner and re-create it so a key change
    /// (e.g. saved in System Setup) takes effect on the next chat without a restart.
    /// Scheduled/specialised agent runners and the MCP toolset are left intact.
    static synchronized void reloadInteractive(ExecutionContextFactory ecf) {
        registry.remove(DEFAULT_CONFIG)
        agentRegistry.remove(DEFAULT_CONFIG)
        currentConfig = [:]
        ensureInteractiveDefault(ecf)
    }

    static boolean isInitialized() { !registry.isEmpty() }

    static List<String> listAgents() {
        registry.values().collect { it.agent().name() }.unique() ?: ['hello-time-agent']
    }

    // ── Session management ────────────────────────────────────────────────────

    static Map createSession(String userId, Map<String, Object> initialState = [:]) {
        String tenantId = initialState?.get('tenantId') as String
        String configId = resolveConfigId(tenantId)
        Runner runner   = registry[configId] ?: registry.values().first()
        if (!runner) throw new IllegalStateException('ADK not initialized — add API key in ADK → Configuration')

        def session = runner.sessionService()
                .createSession(APP_NAME, userId, initialState ?: [:], null)
                .blockingGet()

        sessionOwn[session.id()] = configId
        persistSessionConfigId(session.id(), configId)

        AdkSessionHolder.sessions[session.id()] = []
        AdkSessionHolder.logEvent(session.id(), 'session_created', 'Session created', [sessionId: session.id()])

        [id           : session.id(),
         appName      : APP_NAME,
         userId       : session.userId(),
         state        : session.state() ?: [:],
         events       : [],
         lastUpdateTime: System.currentTimeMillis()]
    }

    static Map getSession(String userId, String sessionId) {
        def runner = runnerForSession(sessionId)
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
        // Use first available runner (sessions are shared via MoquiSessionService)
        def runner = registry.values().first()
        def response = runner?.sessionService()?.listSessions(APP_NAME, userId)?.blockingGet()
        response?.sessions()?.collect { s -> [id: s.id(), appName: APP_NAME, userId: s.userId()] } ?: []
    }

    static void deleteSession(String userId, String sessionId) {
        def runner = runnerForSession(sessionId)
        runner.sessionService().deleteSession(APP_NAME, userId, sessionId).blockingAwait()
        sessionOwn.remove(sessionId)
    }

    // ── Agent execution ───────────────────────────────────────────────────────

    static RunConfig defaultRunConfig() { RunConfig.builder().setMaxLlmCalls(12).build() }

    static List<Map> runAgent(String userId, String sessionId, String text) {
        Content userContent = buildUserContent(text)
        List<Map> events = []
        Throwable[] err  = [null]
        runnerForSession(sessionId).runAsync(userId, sessionId, userContent, defaultRunConfig())
            .blockingSubscribe(
                { Event e -> events << eventToMap(e) },
                { Throwable t -> err[0] = t; logger.error("ADK runAgent error (session={}): {}", sessionId, t.message, t) }
            )
        if (err[0]) throw err[0]
        events
    }

    /**
     * Run a one-off agent turn for the scheduler.
     * Uses a fresh InMemorySessionService to avoid the DB transaction isolation
     * issue: createSession writes to DB within an open (uncommitted) service
     * transaction; a subsequent runAsync on an IO thread cannot see it via
     * MoquiSessionService.getSession. In-memory sessions need no DB round-trip.
     */
    static List<Map> runOneOff(String configId, String userId, String text,
                               Map<String, Object> initialState = [:]) {
        String cid = configId ?: DEFAULT_CONFIG
        LlmAgent agent = agentRegistry[cid] ?: agentRegistry.values().first()
        if (!agent) throw new IllegalStateException('ADK not initialized — add API key in ADK → Configuration')

        // The agent instruction embeds CONTEXT_PREAMBLE with {userId}/{username}/... placeholders
        // that ADK resolves from session state. Seed state with these keys so injectSessionState
        // does not throw "Context variable not found".
        def state = new java.util.concurrent.ConcurrentHashMap<String, Object>(initialState ?: [:])
        // {screenCatalog} appears in the instruction preamble; scheduled/one-off runs
        // have no Flutter client, so default it to avoid "Context variable not found".
        state.putIfAbsent('screenCatalog', '[]')

        def inMemSvc = new InMemorySessionService()
        def session  = inMemSvc.createSession(APP_NAME, userId, state, null).blockingGet()

        Runner oneOff = Runner.builder()
                .agent(agent)
                .appName(APP_NAME)
                .sessionService(inMemSvc)
                .build()

        Content userContent = buildUserContent(text)
        List<Map> events = []
        Throwable[] err  = [null]

        oneOff.runAsync(userId, session.id(), userContent, defaultRunConfig())
              .blockingSubscribe(
                  { Event e -> events << eventToMap(e) },
                  { Throwable t -> err[0] = t; logger.error("ADK runOneOff error (config={}): {}", cid, t.message, t) }
              )

        if (err[0]) throw err[0]
        events
    }

    static void runAgentSse(String userId, String sessionId, String text,
                            Closure eventCallback, Closure doneCallback) {
        Content userContent = buildUserContent(text)
        runnerForSession(sessionId).runAsync(userId, sessionId, userContent, defaultRunConfig())
            .subscribe(
                { Event e -> eventCallback(eventToMap(e)) },
                { Throwable t -> doneCallback(t) },
                { doneCallback(null) }
            )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static String resolveConfigId(String ownerPartyId) {
        if (ownerPartyId && tenantRegistry[ownerPartyId]) return tenantRegistry[ownerPartyId]
        // Fall back to the only/default registered config
        return registry.containsKey(DEFAULT_CONFIG) ? DEFAULT_CONFIG : (registry.keySet().first() ?: DEFAULT_CONFIG)
    }

    private static Runner runnerForSession(String sessionId) {
        String configId = sessionOwn[sessionId] ?: lookupConfigIdFromDb(sessionId) ?: DEFAULT_CONFIG
        if (configId && configId != DEFAULT_CONFIG) sessionOwn[sessionId] = configId  // cache it
        return registry[configId] ?: registry.values().first() ?: { throw new IllegalStateException('ADK not initialized') }()
    }

    private static String lookupConfigIdFromDb(String sessionId) {
        if (!sharedSessionService) return null
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def sv = ec.entity.find('moqui.adk.AdkSession').condition('adkSessionId', sessionId).one()
                return sv?.configId as String
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception ignored) { return null }
    }

    private static void persistSessionConfigId(String sessionId, String configId) {
        if (!sharedSessionService) return
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def sv = ec.entity.find('moqui.adk.AdkSession').condition('adkSessionId', sessionId).one()
                if (sv && sv.configId != configId) { sv.configId = configId; sv.update() }
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception ignored) {}
    }

    private static Content buildUserContent(String text) {
        Content.builder().role('user').parts([Part.fromText(text)]).build()
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

    /** Build a per-agent MCP toolset whose SSE headers carry `adk_config_id` so the
     *  governance gate on the MCP server can resolve the calling agent and its tenant. */
    private static com.google.adk.tools.mcp.McpToolset buildMcpToolset(String configId) {
        if (mcpApiKey == null && sharedSessionService != null) {
            mcpApiKey = generateMcpApiKey(sharedSessionService.ecf)
        }
        Map<String, String> sseHeaders = ['Accept': 'application/json, text/event-stream']
        if (mcpApiKey) {
            sseHeaders['api_key'] = mcpApiKey
        } else {
            sseHeaders['Authorization'] = 'Basic ' + 'SystemSupport:moqui'.bytes.encodeBase64().toString()
        }
        if (configId && configId != DEFAULT_CONFIG) sseHeaders['adk_config_id'] = configId
        String mcpInternalPort = System.getenv('webapp_http_port') ?: '8080'
        def sseParams = com.google.adk.tools.mcp.SseServerParameters.builder()
                .url("http://localhost:${mcpInternalPort}/mcp/sse")
                .headers(sseHeaders)
                .build()
        def prior = configMcpToolsets[configId]
        if (prior != null) { try { prior.close() } catch (Exception ignore) {} }
        def ts = new com.google.adk.tools.mcp.McpToolset(sseParams)
        configMcpToolsets[configId] = ts
        if (mcpToolset == null) mcpToolset = ts   // keep a default reference for legacy paths
        return ts
    }

    /** Read an agent's toolMode (readOnly | scoped | full) from its persisted config.
     *  Defaults to readOnly when unknown so new/unconfigured agents are safe by default. */
    private static String lookupToolMode(String configId) {
        if (!configId || configId == DEFAULT_CONFIG || sharedSessionService == null) return 'full'
        try {
            def ec = sharedSessionService.ecf.getExecutionContext()
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try {
                def cfg = ec.entity.find('moqui.adk.AdkAgentConfig')
                        .condition('adkAgentConfigId', configId).one()
                // null toolMode = legacy row (pre-governance) → keep full tool access.
                return (cfg?.toolMode ?: 'full') as String
            } finally {
                if (!wasDisabled) ec.artifactExecution.enableAuthz()
            }
        } catch (Exception e) {
            logger.warn("lookupToolMode failed for ${configId}: ${e.message}")
            return 'readOnly'
        }
    }

    static void destroy() {
        configMcpToolsets.values().each { ts ->
            try { ts?.close() } catch (Exception e) { logger.warn("Error closing per-config McpToolset: ${e.message}") }
        }
        configMcpToolsets.clear()
        if (mcpToolset != null) {
            try {
                logger.info('Closing ADK McpToolset...')
                mcpToolset.close()
            } catch (Exception e) {
                logger.warn("Error closing McpToolset: ${e.message}", e)
            }
            mcpToolset = null
        }
        registry.clear()
        agentRegistry.clear()
        tenantRegistry.clear()
        sessionOwn.clear()
        providerRegistry.clear()
        sharedSessionService = null
        mcpApiKey = null
    }
}
