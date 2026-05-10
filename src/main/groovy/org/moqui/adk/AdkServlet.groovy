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
import com.google.adk.models.Gemini
import com.google.adk.runner.Runner
import com.google.adk.web.AdkWebServer
import com.google.genai.types.Content
import com.google.genai.types.Part
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AdkServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(AdkServlet.class)

    private ExecutionContextFactoryImpl ecf
    private volatile Runner runner
    private volatile LlmAgent agent
    private AdkSessionStore sessionStore
    private int adkWebPort = 8090
    private Thread adkWebThread
    private final Object runnerLock = new Object()

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)

        ecf = (ExecutionContextFactoryImpl) config.servletContext.getAttribute("executionContextFactory")
        adkWebPort = (config.getInitParameter("adkWebPort") ?: "8090") as int

        sessionStore = new AdkSessionStore(ecf)
        // Runner built lazily on first request — no user context available at init time

        // Publish port so Moqui screens can read it via System.getProperty("adk.web.port")
        System.setProperty("adk.web.port", adkWebPort as String)

        logger.info("AdkServlet initialized, ADK runner and web UI will start on first request")
    }

    private void ensureRunner() {
        if (runner != null) return
        synchronized (runnerLock) {
            if (runner != null) return
            def ec = ecf.getExecutionContext()
            try {
                boolean wasDisabled = ec.artifactExecution.disableAuthz()
                try {
                    def configValue = ec.entity.find("moqui.adk.AdkAgentConfig")
                            .condition("enabled", "Y").list().first

                    String modelName = configValue?.modelName ?: "gemini-2.0-flash"
                    String agentName = configValue?.agentName ?: "MoquiAgent"
                    String systemPrompt = configValue?.systemPrompt ?: "You are a helpful assistant for the GrowERP ERP system."
                    String apiKey = configValue?.apiKey ?: System.getenv("GOOGLE_GENAI_API_KEY") ?: ""

                    def geminiModel = Gemini.builder()
                            .modelName(modelName)
                            .apiKey(apiKey)
                            .build()

                    agent = LlmAgent.builder()
                            .name(agentName)
                            .model(geminiModel)
                            .instruction(systemPrompt)
                            .build()

                    runner = Runner.builder()
                            .agent(agent)
                            .appName("growerp")
                            .sessionService(sessionStore)
                            .build()

                    logger.info("ADK runner built: agent=${agentName}, model=${modelName}")

                    // Start ADK web UI now that agent is ready
                    adkWebThread = new Thread({
                        try {
                            System.setProperty("server.port", adkWebPort as String)
                            AdkWebServer.start(agent)
                        } catch (Exception e) {
                            logger.error("ADK web server failed", e)
                        }
                    })
                    adkWebThread.daemon = true
                    adkWebThread.name = "AdkWebServer"
                    adkWebThread.start()
                } finally {
                    if (!wasDisabled) ec.artifactExecution.enableAuthz()
                }
            } finally {
                ec.destroy()
            }
        }
    }

    @Override
    void destroy() {
        adkWebThread?.interrupt()
        super.destroy()
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.pathInfo ?: "/"

        if (path == "/" || path == "") {
            resp.sendRedirect(req.contextPath + "/adk/ui/")
            return
        }

        if (path.startsWith("/ui")) {
            proxyToAdkWeb(req, resp, path.substring(3) ?: "/")
            return
        }

        if (!authenticate(req, resp)) return

        ensureRunner()

        if (path == "/api/sessions" || path.startsWith("/api/sessions/")) {
            handleSessionsGet(req, resp)
            return
        }

        resp.sendError(HttpServletResponse.SC_NOT_FOUND)
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String path = req.pathInfo ?: "/"

        if (path.startsWith("/ui")) {
            proxyToAdkWeb(req, resp, path.substring(3) ?: "/")
            return
        }

        if (!authenticate(req, resp)) return

        ensureRunner()

        if (path == "/api/run") {
            handleRun(req, resp)
            return
        }

        if (path == "/api/sessions") {
            handleSessionCreate(req, resp)
            return
        }

        resp.sendError(HttpServletResponse.SC_NOT_FOUND)
    }

    private boolean authenticate(HttpServletRequest req, HttpServletResponse resp) {
        def ec = ecf.getExecutionContext()
        try {
            String authHeader = req.getHeader("Authorization")
            boolean loggedIn = false

            if (authHeader?.startsWith("Basic ")) {
                String decoded = new String(Base64.decoder.decode(authHeader.substring(6)))
                int colon = decoded.indexOf(':')
                if (colon > 0) {
                    loggedIn = ec.user.loginUser(decoded.substring(0, colon), decoded.substring(colon + 1))
                }
            } else {
                loggedIn = (ec.user.userId != null)
            }

            if (!loggedIn) {
                resp.setHeader("WWW-Authenticate", 'Basic realm="GrowERP ADK"')
                resp.sendError(HttpServletResponse.SC_UNAUTHORIZED)
                return false
            }
            return true
        } catch (ArtifactAuthorizationException e) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, e.message)
            return false
        } finally {
            ec.destroy()
        }
    }

    private void handleRun(HttpServletRequest req, HttpServletResponse resp) {
        def body = new JsonSlurper().parse(req.reader)
        String sessionId = body.sessionId ?: UUID.randomUUID().toString()
        String userId = body.userId ?: "anonymous"
        String message = body.message ?: ""

        try {
            Content userContent = Content.fromParts(Part.fromText(message))
            List events = runner.runAsync(userId, sessionId, userContent).toList().blockingGet()

            String responseText = events.findAll { event ->
                event.content()?.isPresent() && event.turnComplete()?.orElse(false)
            }.collect { event ->
                event.content().get().parts()?.orElse([])?.collect { part ->
                    part.text()?.orElse("")
                }?.join("")
            }.findAll { it }.join("")

            resp.contentType = "application/json"
            resp.writer.write(JsonOutput.toJson([sessionId: sessionId, response: responseText]))
        } catch (Exception e) {
            logger.error("ADK run failed", e)
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.message)
        }
    }

    private void handleSessionCreate(HttpServletRequest req, HttpServletResponse resp) {
        def body = new JsonSlurper().parse(req.reader)
        String userId = body.userId ?: "anonymous"
        String sessionId = UUID.randomUUID().toString()

        def session = sessionStore.createSession("growerp", userId,
                new java.util.concurrent.ConcurrentHashMap<>(), sessionId).blockingGet()
        resp.contentType = "application/json"
        resp.writer.write(JsonOutput.toJson([sessionId: session.id(), userId: userId]))
    }

    private void handleSessionsGet(HttpServletRequest req, HttpServletResponse resp) {
        resp.contentType = "application/json"
        resp.writer.write(JsonOutput.toJson([sessions: []]))
    }

    // Reverse-proxy a request to the ADK Spring Boot web server on adkWebPort
    private void proxyToAdkWeb(HttpServletRequest req, HttpServletResponse resp, String targetPath) {
        if (!targetPath || targetPath == "") targetPath = "/"
        String queryString = req.queryString ? "?${req.queryString}" : ""
        URL target = new URL("http://localhost:${adkWebPort}${targetPath}${queryString}")

        HttpURLConnection conn = (HttpURLConnection) target.openConnection()
        conn.requestMethod = req.method
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = false

        Set<String> skipHeaders = ["host", "connection", "transfer-encoding"] as Set
        req.headerNames.each { name ->
            if (!skipHeaders.contains(name.toLowerCase())) {
                conn.setRequestProperty(name, req.getHeader(name))
            }
        }

        if (req.method in ["POST", "PUT", "PATCH"]) {
            conn.doOutput = true
            conn.outputStream << req.inputStream
        }

        try {
            conn.connect()
        } catch (ConnectException e) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "ADK web UI not ready yet, try again in a few seconds")
            return
        }

        resp.status = conn.responseCode

        conn.headerFields.each { name, values ->
            if (name && !["transfer-encoding", "connection"].contains(name.toLowerCase())) {
                values.each { resp.addHeader(name, it) }
            }
        }

        InputStream is = conn.responseCode >= 400 ? conn.errorStream : conn.inputStream
        if (is) resp.outputStream << is
    }
}
