# moqui-adk

LLM chat agent embedded as a Moqui Framework component. Talks to Google Gemini via direct REST API calls — no Spring Boot, no extra ports, no external SDK JARs.

---

## What This Is

A native Moqui component that adds an AI chat interface to any Moqui application:

- Chat UI is a native Moqui screen (Bootstrap 3, chat bubbles, multi-turn conversation history)
- Calls the Gemini REST API directly using `java.net.http.HttpClient` (JDK 11+)
- Session history lives in JVM memory (`AdkSessionHolder`) — resets on restart
- Agent config (model, API key, system prompt) managed from the Moqui dashboard
- Moqui authentication guards all transitions

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21+ |
| Moqui Framework | 3.x |
| Gemini API key | [Get one free](https://aistudio.google.com/app/apikey) |

No external dependencies beyond Moqui itself. The built component is a single JAR (`moqui-adk-1.0.0.jar`).

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

Output: `component/moqui-adk/lib/moqui-adk-1.0.0.jar` (one file, ~10 KB).

### 3. Start Moqui

```bash
java -jar moqui.war no-run-es
```

Moqui creates the `ADK_AGENT_CONFIG` table automatically on first start. No separate data-load step required.

---

## Configuration

### Option A — UI

1. Log in at `http://localhost:8080/vapps` (admin: `SystemSupport` / `moqui`)
2. Click **ADK** in the top navigation bar → **Configuration**
3. Fill in:

| Field | Description | Example |
|-------|-------------|---------|
| Agent Name | Identifier for this agent | `MoquiAgent` |
| Model | Gemini model ID | `gemini-2.0-flash` |
| API Key | Your Gemini API key | `AIza…` |
| System Instruction | Agent persona / constraints | See below |

4. Click **Save Configuration**

### Option B — Environment variable

Set any of the following before starting Moqui — no UI config needed:

```bash
export GOOGLE_API_KEY=AIza...
# or GOOGLE_GENAI_API_KEY / GEMINI_API_KEY
java -jar moqui.war no-run-es
```

The component checks the DB first; falls back to env vars if no `enabled=Y` record with an API key exists.

### Recommended system instruction

```
You are a helpful assistant for the GrowERP system.
Help users understand their data, answer questions about orders,
inventory, and customers, and guide them through business processes.
Be concise and precise. When unsure, say so.
```

---

## Usage

**ADK → Dashboard** — shows agent name, model, and configuration status.

**ADK → Chat UI** — chat window. Type a message and press Enter (or click Send). The agent maintains full conversation history within the session.

**ADK → Configuration** — update agent settings. Saving takes effect on the next chat request (no restart needed).

---

## Architecture

```
Browser
  │
  ▼
Moqui screen  /vapps/adk/ChatUI
  │
  │  POST /adk/ChatUI/createSession  →  returns { sessionId }
  │  POST /adk/ChatUI/runAgent       →  returns { response }
  │
  ▼
ChatUI.xml transitions (Groovy inline)
  │
  ├── AdkSessionHolder  (ConcurrentHashMap, JVM memory)
  │   └── sessionId → [ {role,parts}, … ]   ← full conversation history
  │
  └── java.net.http.HttpClient
        └── POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
              body: { system_instruction, contents: [history] }
```

| File | Role |
|------|------|
| `screen/Adk/ChatUI.xml` | Chat UI + `createSession` / `runAgent` transitions |
| `screen/Adk/dashboard.xml` | Status overview (agent name, model, key configured) |
| `screen/Adk/Configuration.xml` | Form to save agent config |
| `service/AdkServices.xml` | `update#AgentConfig`, `create#Session`, `run#Agent` services |
| `entity/AdkEntities.xml` | `AdkAgentConfig` entity |
| `src/.../AdkSessionHolder.groovy` | Static `ConcurrentHashMap` for in-memory session history |
| `data/AdkSecuritySeedData.xml` | `AdkUsers` group + artifact auth rules |

---

## Entities

| Entity | Purpose |
|--------|---------|
| `moqui.adk.AdkAgentConfig` | Agent config: name, model, API key, instruction, enabled flag |

Session history is in-memory only (`AdkSessionHolder`) — not persisted to the database.

---

## Verifying the Installation

### 1. Check JAR built

```bash
ls moqui/runtime/component/moqui-adk/lib/
# Expected: moqui-adk-1.0.0.jar  (one file)
```

### 2. Check component loaded

After starting Moqui, search startup log for:

```
Component moqui-adk loaded
```

### 3. Test via browser

Navigate to `http://localhost:8080/vapps` → **ADK** → **Chat UI** → type a message → agent replies.

### 4. Test via curl

```bash
# Authenticate and grab CSRF token
SESSION=$(curl -s -c /tmp/moqui-cookies.txt -b /tmp/moqui-cookies.txt \
  -X POST http://localhost:8080/apps/Login \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=SystemSupport&password=moqui" -D - | grep -i location)

CSRF=$(curl -s -c /tmp/moqui-cookies.txt -b /tmp/moqui-cookies.txt \
  http://localhost:8080/apps/adk/ChatUI \
  | grep confMoquiSessionToken | sed 's/.*value="\([^"]*\)".*/\1/')

# Create session
curl -s -X POST http://localhost:8080/apps/adk/ChatUI/createSession \
  -b /tmp/moqui-cookies.txt \
  -H "Content-Type: application/json" -H "Accept: application/json" \
  -H "X-CSRF-Token: $CSRF" -d '{}'
# → {"sessionId":"<uuid>"}

# Send message (replace <uuid>)
curl -s -X POST http://localhost:8080/apps/adk/ChatUI/runAgent \
  -b /tmp/moqui-cookies.txt \
  -H "Content-Type: application/json" -H "Accept: application/json" \
  -H "X-CSRF-Token: $CSRF" \
  -d '{"sessionId":"<uuid>","message":"Hello, who are you?"}'
# → {"response":"..."}
```

---

## Troubleshooting

### "Config error — set API key in Configuration"

No API key found in DB or environment. Either:
- Go to **ADK → Configuration** and save an API key, or
- Set `GOOGLE_API_KEY` env var before starting Moqui

### Chat UI shows blank / empty screen

Moqui is serving a cached older version. Restart Moqui.

### Agent returns empty response

Gemini returned no candidate text. Check:
- API key is valid (test at [aistudio.google.com](https://aistudio.google.com))
- Model name is correct (`gemini-2.0-flash` is the default)
- Prompt is not blocked by Gemini safety filters

### `ClassNotFoundException` on Moqui start

Component not built. Run:

```bash
cd moqui && ./gradlew :runtime:component:moqui-adk:jar
```

Then restart Moqui.

---

## Development

### Rebuild after Groovy changes

```bash
cd moqui/runtime/component/moqui-adk
../../../gradlew jar
# restart Moqui
```

Screen XML and service XML changes take effect without rebuild (Moqui hot-reloads them).

### Commit and push

```bash
cd moqui/runtime/component/moqui-adk
git add -A && git commit -m "your change"
git push origin growerp

# Update submodule pointer in moqui-runtime
cd ../..
git add component/moqui-adk
git commit -m "bump moqui-adk submodule"
git push origin growerp
```

---

## Links

- [Gemini API reference](https://ai.google.dev/api/generate-content)
- [Moqui Framework](https://github.com/moqui/moqui-framework)
- [Get a Gemini API Key](https://aistudio.google.com/app/apikey)
- [GrowERP](https://github.com/growerp/growerp)

---

## License

Public domain under CC0 1.0 Universal plus Grant of Patent License, consistent with Moqui Framework.
