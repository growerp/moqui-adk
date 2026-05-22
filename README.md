# moqui-adk

Google ADK (Agent Development Kit) embedded as a Moqui Framework component. Runs LLM agents via the Google ADK Java SDK and serves the official ADK Angular DevUI at `/adk/`.

---

## What This Is

A native Moqui component that integrates the [Google ADK Java SDK](https://github.com/google/adk-java) into any Moqui application:

- **ADK DevUI** — the official Angular chat interface served by Moqui at `http://…/adk/`
- **Google ADK Java SDK** — `LlmAgent` + `InMemoryRunner` handle agent execution and session management
- **HelloTimeAgent** — built-in example agent (tells the current time for a city) used when no custom agent is configured
- **Moqui dashboard** — status overview and configuration screen at `/vapps/adk/`
- No extra processes, no Python, no extra ports — everything runs inside Moqui

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21+ |
| Moqui Framework | 4.x (growerp fork) |
| Gradle | via `moqui/gradlew` |
| Google Gemini API key | [Get one free](https://aistudio.google.com/app/apikey) |

The Google ADK runtime JARs (`google-adk:1.3.0` and transitive deps) are declared in the Moqui framework `build.gradle` as `runtimeOnly` dependencies — they ship inside `moqui.war` and are **not** bundled in the component `lib/` directory.

---

## Installation

### 0. Add ADK runtime dependency to Moqui framework

The component compiles against `google-adk` but does **not** bundle the JARs. They must be declared in the Moqui framework so they ship inside `moqui.war`.

In `moqui/framework/build.gradle`, add to the `dependencies` block:

```groovy
// Google ADK — required by moqui-adk component for LLM agent support
runtimeOnly 'com.google.adk:google-adk:1.3.0' // Apache 2.0
```

> This is already present in the growerp fork of moqui-framework. Skip this step if using that fork.

### 1. Add as submodule of moqui-runtime

```bash
cd moqui/runtime
git submodule add -b growerp https://github.com/growerp/moqui-adk.git component/moqui-adk
git commit -m "add moqui-adk submodule"
```

### 2. Build

From the Moqui root (builds framework WAR + extracts Angular DevUI assets):

```bash
cd moqui
./gradlew build
```

This runs the `extractAdkBrowserAssets` task which:
- Downloads `google-adk-dev:1.3.0` (contains the pre-built Angular SPA)
- Extracts the browser assets to `component/moqui-adk/screen/adk-ui/`
- Patches `index.html` `<base href>` to `/adk/`
- Patches `assets/config/runtime-config.json` `backendUrl` to `/adk`

The component produces a single JAR: `lib/moqui-adk-1.0.0.jar` (~30 KB).

### 3. Load seed data (first run only)

```bash
java -jar moqui.war load types=seed,seed-initial,install no-run-es
```

### 4. Start Moqui

```bash
# With API key via environment variable (simplest):
GOOGLE_API_KEY=AIza... java -jar moqui.war no-run-es

# Or without key (configure via UI after start):
java -jar moqui.war no-run-es
```

---

## Configuration

### Option A — Environment variable (recommended for development)

Set any of the following before starting Moqui:

```bash
export GOOGLE_API_KEY=AIza...
# alternatives: GOOGLE_GENAI_API_KEY or GEMINI_API_KEY
java -jar moqui.war no-run-es
```

HelloTimeAgent is used automatically. No UI config needed.

### Option B — Moqui Admin UI

1. Log in at `http://localhost:8080/vapps` (user: `SystemSupport` / `moqui`)
2. Navigate to **ADK → Configuration**
3. Fill in the form:

| Field | Description | Default |
|-------|-------------|---------|
| Agent Name | Leave blank to use the built-in HelloTimeAgent | _(blank = HelloTimeAgent)_ |
| Model | Gemini model ID | `gemini-2.0-flash` |
| API Key | Your Google Gemini API key | — |
| System Instruction | Agent persona / constraints | — |

4. Click **Save Configuration**

Config takes effect immediately (no restart). DB config takes priority over env vars.

---

## Usage

### ADK DevUI (primary interface)

Navigate to `http://localhost:8080/adk/` — the official Google ADK Angular interface:

1. Select **hello-time-agent** from the dropdown (or your custom agent if configured)
2. Click **+ New Session**
3. Type a message, e.g. `What time is it in Tokyo?`
4. The agent calls the `getCurrentTime` tool and replies via Gemini

The Trace / Events / State / Sessions tabs show full invocation details.

### Moqui Dashboard

Navigate to `http://localhost:8080/vapps` → **ADK → Dashboard** — shows agent name, model, and configuration status with a link to the DevUI.

---

## Architecture

```
Browser
  │
  ├── GET  /adk/              → Angular DevUI (index.html)
  ├── GET  /adk/main-*.js     → Angular static assets
  │
  ├── GET  /adk/list-apps     ─┐
  ├── POST /adk/apps/…/sessions│ AdkDevServlet (Jakarta Servlet)
  ├── POST /adk/run_sse       ─┘    │
  │                                 ▼
  │                            AdkManager (Groovy singleton)
  │                                 │
  │                       ┌─────────┴──────────┐
  │                       │                    │
  │                  InMemoryRunner      HelloTimeAgent
  │                  (ADK Java SDK)      (or custom LlmAgent)
  │                       │
  │                       ▼
  │              Gemini API (google.generativeai)
  │
  └── /vapps/adk/*         → Moqui screens (dashboard, configuration)
```

### Key files

| File | Role |
|------|------|
| `src/…/AdkDevServlet.groovy` | Jakarta Servlet at `/adk` and `/adk/*` — serves Angular SPA + ADK REST API |
| `src/…/AdkManager.groovy` | Singleton: `LlmAgent` + `InMemoryRunner` + lazy init + session management |
| `src/…/HelloTimeAgent.groovy` | Example agent — tells current time for a city using a function tool |
| `src/…/AdkSessionHolder.groovy` | In-memory event log for the Moqui inspector panel |
| `screen/adk-ui/` | Extracted Angular DevUI assets (build artifact, gitignored) |
| `screen/Adk/dashboard.xml` | Moqui status dashboard |
| `screen/Adk/Configuration.xml` | Agent config form |
| `service/AdkServices.xml` | `update#AgentConfig`, `create#Session`, `run#Agent` Moqui services |
| `entity/AdkEntities.xml` | `moqui.adk.AdkAgentConfig` entity |
| `MoquiConf.xml` | Servlet registration + screen facade |
| `build.gradle` | `extractAdkBrowserAssets` task + `adkDevAssets` configuration |
| `data/AdkSecuritySeedData.xml` | Auth rules for `/adk/*` + `AdkUsers` user group |

### ADK REST API (implemented by AdkDevServlet)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/adk/list-apps` | Returns `["hello-time-agent"]` (or current agent name) |
| `POST` | `/adk/apps/{app}/users/{uid}/sessions` | Create session |
| `GET` | `/adk/apps/{app}/users/{uid}/sessions` | List sessions |
| `GET` | `/adk/apps/{app}/users/{uid}/sessions/{sid}` | Get session |
| `DELETE` | `/adk/apps/{app}/users/{uid}/sessions/{sid}` | Delete session |
| `POST` | `/adk/run` | Synchronous run — returns JSON event array |
| `POST` | `/adk/run_sse` | Streaming run — Server-Sent Events |

---

## HelloTimeAgent

The built-in example agent is ported from the [ADK Java quickstart](https://github.com/google/adk-java). It demonstrates:

- `FunctionTool.create(Class, methodName)` — registering a Groovy method as an ADK tool
- `@Schema` annotations for parameter descriptions consumed by Gemini
- Tool returning a `Map<String, String>` result

Source: [`src/main/groovy/org/moqui/adk/HelloTimeAgent.groovy`](src/main/groovy/org/moqui/adk/HelloTimeAgent.groovy)

Try asking: `What time is it in London?` or `amsterdam time?`

---

## Adding a Custom Agent

Replace HelloTimeAgent with your own `LlmAgent`:

1. Go to **ADK → Configuration**, enter a custom **Agent Name**, **Model**, **API Key**, and **System Instruction**, then save.
2. The runner reinitializes immediately with a plain `LlmAgent` (no function tools) using your config.

For an agent with custom tools, extend `AdkManager.init()` to detect your agent name and wire in your `LlmAgent` instance with `FunctionTool` entries.

---

## Entities

| Entity | Purpose |
|--------|---------|
| `moqui.adk.AdkAgentConfig` | Agent config: name, model, API key, instruction, enabled flag |

Session state is held in-memory by the ADK `InMemoryRunner` — not persisted to the database. Sessions are lost on Moqui restart.

---

## Troubleshooting

### DevUI shows "Failed to load agents"

The servlet isn't responding. Check:
- Moqui started without errors (`grep -i 'adk\|error' /tmp/moqui.log`)
- `screen/adk-ui/index.html` exists (run `./gradlew build` if missing)

### Error: "API key must either be provided…"

No API key configured. Set `GOOGLE_API_KEY` env var or configure via **ADK → Configuration**.

### Agent listed but session creation fails (503)

`AdkManager` not initialized. Check Moqui log for init errors. Try reloading `/adk/` to trigger lazy init.

### `ClassNotFoundException` on startup

Component not built. Run:

```bash
cd moqui && ./gradlew :runtime:component:moqui-adk:build
```

Then restart Moqui.

### screen/adk-ui/ is empty

Run the Gradle extraction task:

```bash
cd moqui && ./gradlew :runtime:component:moqui-adk:extractAdkBrowserAssets
```

---

## Development

### Rebuild after Groovy changes

```bash
cd moqui
./gradlew :runtime:component:moqui-adk:build
# restart Moqui to pick up the new JAR
```

Screen XML and service XML hot-reload without rebuild.

### Rebuild the full WAR (framework changes)

```bash
cd moqui && ./gradlew build
java -jar moqui.war no-run-es
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

- [Google ADK Java SDK](https://github.com/google/adk-java)
- [ADK Java Quickstart](https://google.github.io/adk-docs/get-started/quickstart-java/)
- [Get a Gemini API Key](https://aistudio.google.com/app/apikey)
- [Moqui Framework](https://github.com/moqui/moqui-framework)
- [GrowERP](https://github.com/growerp/growerp)

---

## License

Public domain under CC0 1.0 Universal plus Grant of Patent License, consistent with Moqui Framework.
