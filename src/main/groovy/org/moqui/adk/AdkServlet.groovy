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

import jakarta.servlet.ServletConfig
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Auth-gating reverse proxy to a standalone ADK server.
// All requests to /adk/* are authenticated via Moqui session or Basic auth,
// then forwarded verbatim to the configured ADK server URL.
class AdkServlet extends HttpServlet {
    protected final static Logger logger = LoggerFactory.getLogger(AdkServlet.class)

    private ExecutionContextFactoryImpl ecf
    private String defaultAdkServerUrl = "http://localhost:8090"

    @Override
    void init(ServletConfig config) throws ServletException {
        super.init(config)
        ecf = (ExecutionContextFactoryImpl) config.servletContext.getAttribute("executionContextFactory")
        String param = config.getInitParameter("adkServerUrl")
        if (param) defaultAdkServerUrl = param
        logger.info("AdkServlet initialized, default ADK server: ${defaultAdkServerUrl}")
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!authenticate(req, resp)) return
        String path = req.pathInfo ?: "/"
        if (path == "/" || path == "") { resp.sendRedirect(req.contextPath + "/adk/dev-ui"); return }
        proxyToAdk(req, resp, path)
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!authenticate(req, resp)) return
        proxyToAdk(req, resp, req.pathInfo ?: "/")
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!authenticate(req, resp)) return
        proxyToAdk(req, resp, req.pathInfo ?: "/")
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        if (!authenticate(req, resp)) return
        proxyToAdk(req, resp, req.pathInfo ?: "/")
    }

    private boolean authenticate(HttpServletRequest req, HttpServletResponse resp) {
        def ec = ecf.getExecutionContext()
        try {
            String authHeader = req.getHeader("Authorization")
            boolean loggedIn = false
            if (authHeader?.startsWith("Basic ")) {
                String decoded = new String(Base64.decoder.decode(authHeader.substring(6)))
                int colon = decoded.indexOf(':')
                if (colon > 0) loggedIn = ec.user.loginUser(decoded.substring(0, colon), decoded.substring(colon + 1))
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

    private void proxyToAdk(HttpServletRequest req, HttpServletResponse resp, String targetPath) {
        if (!targetPath || targetPath == "") targetPath = "/"
        String serverUrl = getAdkServerUrl()
        String queryString = req.queryString ? "?${req.queryString}" : ""
        URL target = new URL("${serverUrl}${targetPath}${queryString}")

        HttpURLConnection conn = (HttpURLConnection) target.openConnection()
        conn.requestMethod = req.method
        conn.connectTimeout = 5000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = false

        Set<String> skipHeaders = ["host", "connection", "transfer-encoding"] as Set
        req.headerNames.each { name ->
            if (!skipHeaders.contains(name.toLowerCase())) conn.setRequestProperty(name, req.getHeader(name))
        }

        if (req.method in ["POST", "PUT", "PATCH"]) { conn.doOutput = true; conn.outputStream << req.inputStream }

        try {
            conn.connect()
        } catch (ConnectException e) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "ADK server not available at ${serverUrl} — start it and try again")
            return
        }

        resp.status = conn.responseCode
        conn.headerFields.each { name, values ->
            if (name && !["transfer-encoding", "connection"].contains(name.toLowerCase())) {
                values.each { value ->
                    // Rewrite Location headers so redirects stay within the proxy
                    String rewritten = (name.toLowerCase() == "location" && value?.startsWith(serverUrl))
                            ? "/adk" + value.substring(serverUrl.length()) : value
                    resp.addHeader(name, rewritten)
                }
            }
        }

        InputStream is = conn.responseCode >= 400 ? conn.errorStream : conn.inputStream
        if (is) resp.outputStream << is
    }

    private String getAdkServerUrl() {
        def ec = ecf.getExecutionContext()
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            def config = ec.entity.find("moqui.adk.AdkAgentConfig").condition("enabled", "Y").one()
            return config?.adkServerUrl ?: defaultAdkServerUrl
        } catch (Exception e) {
            return defaultAdkServerUrl
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
            ec.destroy()
        }
    }
}
