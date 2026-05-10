# moqui-adk

Google Agent Development Kit (ADK) as a Moqui Framework component.

Embeds ADK's agent runtime and web UI directly into Moqui, accessible as a top-level "ADK" group in the system navigation bar — no separate server to manage.

---

## What This Is

[Google ADK](https://google.github.io/adk-docs/) is an open-source Java toolkit for building AI agents backed by Gemini models. This component wires it into Moqui as a first-class citizen:

- ADK agent runtime runs inside the Moqui JVM
- ADK web UI (chat interface + session inspector) is proxied through Moqui at `/adk/ui/`
- Sessions are persisted to Moqui entities (survive restarts, auditable)
- Agent configuration (model, API key, system prompt) is managed from the Moqui dashboard
- Moqui artifact authorization controls who can access the ADK API

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21+ |
| Moqui Framework | 3.x |
| Groovy | 4.x (bundled with Moqui) |
| Gemini API key | [Get one free](https://aistudio.google.com/app/apikey) |

---

## Installation

### 1. Add as submodule of moqui-runtime

```bash
cd moqui/runtime
git submodule add -b growerp https://github.com/growerp/moqui-adk.git component/moqui-adk
git commit -m "add moqui-adk submodule"
```

### 2. Build the component

From the Moqui root:

```bash
./gradlew :runtime:component:moqui-adk:jar
```

This downloads all ADK dependencies and copies them to `component/moqui-adk/lib/`.

### 3. Load seed data

```bash
java -jar moqui.war load types=seed,seed-initial,install no-run-es
```

This creates the `AdkUsers` security group and sets up artifact authorization rules.

### 4. Start Moqui

```bash
java -jar moqui.war no-run-es
```

On startup, `AdkServlet` initializes the ADK runner and launches the ADK web UI (Spring Boot) on port **8090** in a background thread.

---

## Configuration

### First-time setup via the dashboard

1. Log in to Moqui at `http://localhost:8080/vapps` (admin: `SystemSupport` / `moqui`)
2. Click **ADK** in the top navigation bar
3. Click **Configuration**
4. Fill in:

| Field | Description | Example |
|-------|-------------|---------|
| Agent Name | Identifier for this agent | `MoquiAgent` |
| Model | Gemini model ID | `gemini-2.0-flash` |
| API Key | Your Gemini API key | `AIza...` |
| System Prompt | Agent behavior instructions | See below |

5. Click **Save Configuration**

### Recommended system prompt

```
You are a helpful assistant for the GrowERP system.
You help users understand their data, answer questions about orders,
inventory, and customers, and guide them through business processes.
Always be concise and precise. When you are unsure, say so.
```

### Change the ADK web UI port

Add an init-param to `component.xml` or override in `MoquiConf.xml`:

```xml
<servlet name="AdkServlet" class="org.moqui.adk.AdkServlet" ...>
    <init-param name="adkWebPort" value="8091"/>
</servlet>
```

Restart Moqui after changing this.

---

## Using the ADK Dashboard

### Dashboard

Navigate to **ADK → Dashboard** for a live status overview:

- Agent name and model currently active
- ADK web UI port and readiness status
- Total session count
- Quick links to Chat UI and Configuration

### Chat UI

Navigate to **ADK → Chat UI** to open the ADK web interface embedded in an iframe.

The ADK web UI provides:
- A chat window to send messages to the agent
- Session management (create, list, switch sessions)
- Event inspector showing the agent's reasoning steps
- Tool call details (if tools are configured)

**First use:** The ADK web UI may take 10–20 seconds to become available after Moqui starts. If you see "ADK web UI not ready yet", wait a moment and reload.

### Configuration

Navigate to **ADK → Configuration** to update agent settings at runtime. Changes take effect on the next agent build (currently requires a Moqui restart to reload the runner with the new config).

---

## Direct API Access

The servlet also exposes a REST API at `/adk/api/*` (requires authentication):

### Create a session

```bash
curl -u SystemSupport:moqui -X POST http://localhost:8080/adk/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId": "SystemSupport"}'
```

Response:
```json
{"sessionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479", "userId": "SystemSupport"}
```

### Run the agent

```bash
curl -u SystemSupport:moqui -X POST http://localhost:8080/adk/api/run \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "userId": "SystemSupport",
    "message": "How many open orders do we have?"
  }'
```

Response:
```json
{"sessionId": "f47ac10b-...", "response": "I don't have direct database access, but I can help you navigate to the order management screens..."}
```

---

## Architecture

```
Moqui JVM
┌──────────────────────────────────────────────────────┐
│                                                      │
│  Browser ──▶ /vapps/adk  ──▶ Moqui Screen (Adk.xml) │
│                                                      │
│  Browser ──▶ /adk/ui/*  ──▶ AdkServlet ──┐          │
│                               (proxy)    │          │
│  REST    ──▶ /adk/api/* ──▶ AdkServlet   │          │
│                                │         │          │
│                         ┌──────┘    ┌────▼────────┐ │
│                         │           │ ADK Spring  │ │
│                    ADK Runner       │ Boot :8090  │ │
│                         │           └─────────────┘ │
│                    BaseSessionService               │
│                         │                           │
│                    Moqui Entities                   │
│              (AdkSession, AdkSessionEvent)          │
└──────────────────────────────────────────────────────┘
```

| Component | Role |
|-----------|------|
| `AdkServlet` | Jakarta servlet on `/adk/*`; proxies web UI, handles REST API |
| `AdkSessionStore` | Implements `BaseSessionService`; persists sessions to Moqui entities |
| `AdkWebServer` | Google ADK Spring Boot app running on port 8090 (background thread) |
| Moqui screens | Dashboard, Chat UI iframe, Configuration form |
| Seed data | `AdkUsers` group, artifact auth for `/adk/api/*` |

---

## Security

### User groups

| Group | Access |
|-------|--------|
| `ADMIN` | Full ADK access |
| `AdkUsers` | Access to `/adk/api/*` endpoints |

To grant a user ADK access:

```xml
<!-- In your seed data or via the Security admin screen -->
<UserGroupMember userGroupId="AdkUsers" userId="yourUserId"/>
```

The ADK web UI at `/adk/ui/*` is proxied without Moqui auth — the proxy target (port 8090) is only reachable from localhost. Production deployments should add network-level controls to port 8090 if needed.

### API key storage

The Gemini API key is stored encrypted in the `AdkAgentConfig` entity using Moqui's built-in field encryption (`encrypt="true"`). It is never returned in plaintext through the Configuration screen.

---

## Entities

| Entity | Purpose |
|--------|---------|
| `moqui.adk.AdkAgentConfig` | Agent config: name, model, API key, system prompt |
| `moqui.adk.AdkSession` | One row per conversation session |
| `moqui.adk.AdkSessionEvent` | Full event log per session (ADK event JSON) |

---

## Verifying the Installation

Run these checks in order after completing the installation steps.

### 1. Check JAR was built

```bash
ls moqui/runtime/component/moqui-adk/lib/google-adk*.jar
```

Expected: one or more `google-adk-*.jar` files. If missing, run:

```bash
cd moqui && ./gradlew :runtime:component:moqui-adk:jar
```

### 2. Check Moqui loaded the component

After starting Moqui, search the startup log for:

```
Component moqui-adk loaded
AdkServlet initialized
```

If `AdkServlet initialized` is missing, the servlet was not registered. Verify `MoquiConf.xml` contains the `<webapp-list>` block with the servlet.

### 3. Check the servlet responds

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/adk/
```

Expected: `302` (redirect to `/adk/ui/`). A `404` means the servlet is not mapped — check `MoquiConf.xml`.

### 4. Check seed data loaded (security group exists)

```bash
curl -u SystemSupport:moqui \
  "http://localhost:8080/rest/s1/moqui/UserGroups?userGroupId=AdkUsers"
```

Expected: JSON with `userGroupId: "AdkUsers"`. If empty, re-run:

```bash
java -jar moqui.war load types=seed no-run-es
```

### 5. Check API authentication

```bash
# Should succeed (200)
curl -u SystemSupport:moqui -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/adk/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId":"SystemSupport"}'

# Should fail (401)
curl -s -o /dev/null -w "%{http_code}" \
  -X POST http://localhost:8080/adk/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId":"SystemSupport"}'
```

### 6. Create a session and run the agent

```bash
# Step 1: create session, capture sessionId
SESSION=$(curl -u SystemSupport:moqui -s -X POST http://localhost:8080/adk/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId":"SystemSupport"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['sessionId'])")

echo "Session: $SESSION"

# Step 2: run agent (requires API key configured in ADK → Configuration)
curl -u SystemSupport:moqui -X POST http://localhost:8080/adk/api/run \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SESSION\",\"userId\":\"SystemSupport\",\"message\":\"Hello, who are you?\"}"
```

Expected: JSON with `response` field containing agent text.

### 7. Check ADK web UI proxy

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/adk/ui/
```

Expected: `200`. A `503` means the ADK Spring Boot server has not started yet — wait 15–20 seconds and retry. Check logs for Spring Boot startup output on port 8090.

### 8. Check dashboard in browser

Navigate to `http://localhost:8080/vapps` → log in → click **ADK** in the top nav bar.

- **Dashboard** should show agent name, model, and session count
- **Chat UI** tab should render the ADK web interface
- **Configuration** tab should show the config form

---

## Troubleshooting

### ADK web UI shows "not ready yet"

The Spring Boot embedded server takes 10–20 seconds to start. Reload the Chat UI tab. Check Moqui logs for `AdkWebServer started` or Spring Boot startup messages.

### `ClassNotFoundException` on Moqui start

The component was not built before starting Moqui. Run:

```bash
./gradlew :runtime:component:moqui-adk:jar
```

Then restart Moqui.

### Agent not responding / `GOOGLE_GENAI_API_KEY` error

The Gemini API key must be set. Go to **ADK → Configuration**, enter your API key, save, then restart Moqui (the runner is built once at startup).

Alternatively, set the environment variable before starting Moqui:

```bash
export GOOGLE_GENAI_API_KEY=AIza...
java -jar moqui.war no-run-es
```

### Port 8090 already in use

Change the port in `component.xml` (see Configuration section above) or stop the conflicting process:

```bash
fuser -k 8090/tcp
```

### Sessions not persisting across restarts

Verify the database tables were created. After `load types=seed,seed-initial,install`, check that `MOQUI_ADK_SESSION` and `MOQUI_ADK_SESSION_EVENT` tables exist in your database.

---

## Development

### Update submodule after changes

After editing files in `component/moqui-adk/`, rebuild and push:

```bash
cd moqui/runtime/component/moqui-adk
git add -A && git commit -m "your change"
git push origin growerp

# Update the submodule pointer in moqui-runtime
cd ../..
git add component/moqui-adk
git commit -m "bump moqui-adk submodule"
git push origin growerp
```

### Rebuild without restarting Moqui

Moqui does not hot-reload Java components. After rebuilding, restart:

```bash
./gradlew :runtime:component:moqui-adk:jar
# then restart java -jar moqui.war
```

---

## Links

- [Google ADK Documentation](https://google.github.io/adk-docs/)
- [Google ADK Java on GitHub](https://github.com/google/adk-java)
- [Moqui Framework](https://github.com/moqui/moqui-framework)
- [Get a Gemini API Key](https://aistudio.google.com/app/apikey)
- [GrowERP](https://github.com/growerp/growerpAdkGenui)

---

## License

Public domain under CC0 1.0 Universal plus Grant of Patent License, consistent with Moqui Framework.
