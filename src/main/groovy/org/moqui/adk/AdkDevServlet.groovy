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

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.moqui.context.ExecutionContextFactory
import org.moqui.resource.ResourceReference

/**
 * Serves the ADK Angular DevUI at /adk/* together with the ADK REST API.
 *
 * Static SPA assets are loaded from component://moqui-adk/screen/adk-ui/
 * (populated at Gradle build time by the extractAdkBrowserAssets task).
 *
 * ADK REST endpoints implemented:
 *   GET  /list-apps
 *   POST /apps/{app}/users/{uid}/sessions
 *   GET  /apps/{app}/users/{uid}/sessions[/{sid}]
 *   DELETE /apps/{app}/users/{uid}/sessions/{sid}
 *   POST /run        — synchronous, returns event array
 *   POST /run_sse    — Server-Sent Events stream
 */
class AdkDevServlet extends HttpServlet {

    static final String STATIC_ROOT = 'component://moqui-adk/screen/adk-ui'

    static final Map<String, String> MIME_TYPES = [
        '.html' : 'text/html; charset=utf-8',
        '.js'   : 'application/javascript',
        '.mjs'  : 'application/javascript',
        '.css'  : 'text/css',
        '.svg'  : 'image/svg+xml',
        '.json' : 'application/json',
        '.png'  : 'image/png',
        '.jpg'  : 'image/jpeg',
        '.ico'  : 'image/x-icon',
        '.woff' : 'font/woff',
        '.woff2': 'font/woff2',
        '.txt'  : 'text/plain',
    ].asImmutable()

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
        String pathInfo = req.pathInfo ?: '/'
        String method   = req.method

        resp.setHeader('Cache-Control', 'no-cache, no-store')

        // ── API routes ────────────────────────────────────────────────────────
        if (pathInfo == '/list-apps' || pathInfo == '/list-apps/') {
            AdkManager.lazyInit(ecf(req))
            handleListApps(resp)
            return
        }
        if (pathInfo.startsWith('/apps/')) {
            AdkManager.lazyInit(ecf(req))
            handleApps(pathInfo, method, req, resp)
            return
        }
        if (pathInfo == '/run' && method == 'POST') {
            AdkManager.lazyInit(ecf(req))
            handleRun(req, resp, false)
            return
        }
        if (pathInfo == '/run_sse' && method == 'POST') {
            AdkManager.lazyInit(ecf(req))
            handleRun(req, resp, true)
            return
        }

        // ── Static SPA assets ─────────────────────────────────────────────────
        serveStatic(pathInfo, resp)
    }

    // ── API handlers ──────────────────────────────────────────────────────────

    private void handleListApps(HttpServletResponse resp) {
        json(resp, AdkManager.listAgents())
    }

    private void handleApps(String path, String method, HttpServletRequest req, HttpServletResponse resp) {
        // path: /apps/{app}/users/{userId}/sessions[/{sessionId}]
        String[] parts = path.split('/')
        if (parts.length < 6) { resp.sendError(400, 'Invalid path'); return }
        String userId    = parts[4]
        String sessionId = parts.length > 6 ? parts[6] : null

        switch (method) {
            case 'POST':
                json(resp, AdkManager.createSession(userId))
                break
            case 'GET':

                if (sessionId) {
                    def s = AdkManager.getSession(userId, sessionId)
                    if (!s) { resp.sendError(404); return }
                    json(resp, s)
                } else {
                    json(resp, AdkManager.listSessions(userId))
                }
                break
            case 'DELETE':
                if (sessionId) AdkManager.deleteSession(userId, sessionId)
                resp.status = 200
                break
            default:
                resp.sendError(405)
        }
    }

    private void handleRun(HttpServletRequest req, HttpServletResponse resp, boolean sse) {

        def body      = new JsonSlurper().parse(req.inputStream)
        String userId = (body.userId    ?: 'anonymous') as String
        String sid    = (body.sessionId ?: '') as String
        String text   = (body.newMessage?.parts?.find { it.text }?.text ?: '') as String

        if (!sid) sid = AdkManager.createSession(userId).id as String

        if (sse) {
            resp.contentType = 'text/event-stream; charset=utf-8'
            resp.setHeader('X-Accel-Buffering', 'no')
            resp.setHeader('Connection', 'keep-alive')
            def writer = resp.writer
            AdkManager.runAgentSse(userId, sid, text,
                { Map event ->
                    writer.write("data: ${JsonOutput.toJson(event)}\n\n")
                    writer.flush()
                },
                { Throwable err ->
                    if (err) writer.write("data: ${JsonOutput.toJson([error: err.message])}\n\n")
                    writer.write("data: [DONE]\n\n")
                    writer.flush()
                }
            )
        } else {
            json(resp, AdkManager.runAgent(userId, sid, text))
        }
    }

    // ── Static file serving ───────────────────────────────────────────────────

    private void serveStatic(String path, HttpServletResponse resp) {
        if (path == '/' || path.isEmpty()) path = '/index.html'
        // SPA client-side routing: paths without a file extension → index.html
        if (!path.contains('.')) path = '/index.html'

        String ext      = path.contains('.') ? path.substring(path.lastIndexOf('.')) : ''
        resp.contentType = (MIME_TYPES[ext] ?: 'application/octet-stream')

        ExecutionContextFactory ecf =
            (ExecutionContextFactory) getServletContext().getAttribute('executionContextFactory')

        if (ecf == null) { resp.sendError(503, 'ExecutionContextFactory not available'); return }

        ResourceReference ref = ecf.resource.getLocationReference("${STATIC_ROOT}${path}")
        if (!ref || !ref.getExists()) {
            if (path != '/index.html') {
                serveStatic('/index.html', resp)  // SPA fallback
            } else {
                resp.sendError(404, "ADK DevUI assets not found. Run './gradlew build' first.")
            }
            return
        }

        InputStream stream = ref.openStream()
        try {
            resp.outputStream << stream
        } finally {
            stream?.close()
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private ExecutionContextFactory ecf(HttpServletRequest req) {
        (ExecutionContextFactory) req.servletContext.getAttribute('executionContextFactory')
    }

    private static void json(HttpServletResponse resp, Object data) {
        resp.contentType = 'application/json; charset=utf-8'
        resp.writer.write(JsonOutput.toJson(data))
    }
}
