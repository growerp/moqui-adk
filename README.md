# moqui-adk

Google Agent Development Kit (ADK) embedded as a Moqui Framework component.

Runs an ADK agent inside the Moqui JVM — no separate server, no extra ports. Chat UI and configuration live as native Moqui screens.

---

## What This Is

[Google ADK Java](https://github.com/google/adk-java) is an open-source toolkit for building AI agents backed by Gemini models. This component wires it directly into Moqui:

- ADK `Runner` + `InMemorySessionService` run as a singleton inside the Moqui JVM
- Chat UI is a native Moqui screen (no iframe, no Spring Boot server)
- Agent config (model, API key, system prompt) managed from the Moqui dashboard
- Moqui authentication guards all agent calls

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21+ |
| Moqui Framework | 3.x |
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

This downloads `google-adk:1.2.0` and all transitive dependencies into `component/moqui-adk/lib/`.

### 3. Load seed data

```bash
java -jar moqui.war load types=seed,seed-initial,install no-run-es
```

### 4. Start Moqui

```bash
java -jar moqui.war no-run-es
```

No extra ports, no background threads. The ADK runner initializes lazily on the first chat request.

---

## Configuration

1. Log in at `http://localhost:8080/vapps` (admin: `SystemSupport` / `moqui`)
2. Click **ADK** in the top navigation bar
3. Click **Configuration**
4. Fill in:

| Field | Description | Example |
|-------|-------------|---------|
| Agent Name | Identifier for this agent | `MoquiAgent` |
| Model | Gemini model ID | `gemini-2.0-flash` |
| API Key | Your Gemini API key | `AIza…` |
| System Instruction | Agent behavior | See below |

5. Click **Save Configuration**

### Recommended system instruction

```
You are a helpful assistant for the GrowERP system.
Help users understand their data, answer questions about orders,
inventory, and customers, and guide them through business processes.
Be concise and precise. When unsure, say so.
```

---

## Using the ADK Dashboard

### Dashboard

**ADK → Dashboard** — shows agent name, model, and whether the API key is configured.

### Chat UI

**ADK → Chat UI** — native chat window. Sessions are created automatically; send a message and receive the agent's reply. Session state lives in the Moqui JVM's `InMemorySessionService` (resets on restart).

### Configuration

**ADK → Configuration** — update agent settings. Saving resets the running agent singleton so changes take effect immediately without a Moqui restart.

---

## Architecture

```
Moqui JVM (single process, single port)
┌─────────────────────────────────────────────────────────┐
│                                                         │
│  Browser ──▶ /vapps/adk ──▶ Moqui Screen               │
│                               │                         │
│                    Moqui Transition (JSON)               │
│                     createSession / runAgent             │
│                               │                         │
│                      AdkServices.xml (Groovy)            │
│                               │                         │
│                      AdkAgentManager (singleton)         │
│                      ├── Runner                          │
│                      ├── LlmAgent → Gemini API           │
│                      └── InMemorySessionService          │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

| Component | Role |
|-----------|------|
| `AdkAgentManager` | Singleton; holds Runner + InMemorySessionService; lazy init on first call |
| `AdkServices.xml` | `create#Session`, `run#Agent`, `update#AgentConfig` — called via Moqui transitions |
| `screen/Adk/ChatUI.xml` | Native chat UI; uses Moqui JSON transitions for session/message calls |
| `screen/Adk/Configuration.xml` | Form to set model, API key, system instruction |
| `screen/Adk/dashboard.xml` | Status overview |
| `data/AdkSecuritySeedData.xml` | `AdkUsers` group + artifact auth |

---

## Entities

| Entity | Purpose |
|--------|---------|
| `moqui.adk.AdkAgentConfig` | Agent config: name, model, API key, instruction, enabled flag |

Sessions live in `InMemorySessionService` (JVM memory only — no DB persistence yet).

---

## Verifying the Installation

### 1. Check JARs built

```bash
ls moqui/runtime/component/moqui-adk/lib/google-adk*.jar
```

Expected: `google-adk-1.2.0.jar` present. If missing:

```bash
cd moqui && ./gradlew :runtime:component:moqui-adk:jar
```

### 2. Check component loaded

After starting Moqui, search startup log for:

```
Component moqui-adk loaded
```

### 3. Check seed data

```bash
curl -u SystemSupport:moqui \
  "http://localhost:8080/rest/s1/moqui/UserGroups?userGroupId=AdkUsers"
```

Expected: JSON row with `userGroupId: "AdkUsers"`. If missing, re-run the load step.

### 4. Test via browser

Navigate to `http://localhost:8080/vapps` → **ADK** → **Configuration** → enter API key → Save.

Then **ADK → Chat UI** → type a message → agent replies.

### 5. Test via Moqui transition endpoints

```bash
# Create session
curl -u SystemSupport:moqui -s -X POST \
  http://localhost:8080/vapps/adk/ChatUI/createSession \
  -H "Content-Type: application/json" -d '{}'

# Response: {"sessionId":"<uuid>"}

# Run agent (replace <uuid>)
curl -u SystemSupport:moqui -s -X POST \
  http://localhost:8080/vapps/adk/ChatUI/runAgent \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"<uuid>","message":"Hello, who are you?"}'

# Response: {"response":"..."}
```

---

## Troubleshooting

### "Config error — set API key in Configuration"

No `AdkAgentConfig` row with `enabled=Y` and a non-empty `apiKey`. Go to **ADK → Configuration** and save.

### `ClassNotFoundException` on Moqui start

Component not built. Run:

```bash
cd moqui && ./gradlew :runtime:component:moqui-adk:jar
```

Then restart Moqui.

### Agent returns empty response

The Gemini model returned no `finalResponse` event. Try a simpler prompt. Check that the API key is valid and the model name (`gemini-2.0-flash`) is accessible on your account.

### Config changes not taking effect

`update#AgentConfig` calls `AdkAgentManager.reset()` — the singleton is cleared and rebuilt on the next request. No restart needed.

---

## Development

### Rebuild after code changes

```bash
cd moqui/runtime/component/moqui-adk
../../../gradlew jar
# restart Moqui
```

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

- [Google ADK Documentation](https://google.github.io/adk-docs/)
- [Google ADK Java on GitHub](https://github.com/google/adk-java)
- [Moqui Framework](https://github.com/moqui/moqui-framework)
- [Get a Gemini API Key](https://aistudio.google.com/app/apikey)
- [GrowERP](https://github.com/growerp/growerp)

---

## License

Public domain under CC0 1.0 Universal plus Grant of Patent License, consistent with Moqui Framework.
