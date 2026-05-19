# checkpoint-ai

A durable AI documentation agent built on **Temporal** and **Spring Boot**.

Given a Java repository URL, checkpoint-ai analyses every class, generates per-module narratives, produces an architectural overview, and assembles a complete markdown document — with a human-in-the-loop approval gate before publishing. If the server crashes mid-way, the workflow resumes exactly where it stopped. No LLM call is ever repeated.

LLM calls are made through [Spring AI](https://docs.spring.io/spring-ai/reference/), which supports swapping the model provider with a configuration change — no code changes required. The project ships with Google Gemini configured by default, but OpenAI, Anthropic Claude, Azure OpenAI, Ollama (local), and others are all supported.

---

## Features

- **Durable execution** — powered by Temporal; survives server crashes mid-workflow and resumes from the exact activity that was in progress, not from the beginning
- **Incremental re-runs** — SHA-256 hashes each source file after an approved run; subsequent runs skip unchanged modules entirely, reducing LLM calls and cost proportionally
- **Heartbeating** — activities send a heartbeat every 30 seconds; Temporal detects a dead worker and retries within seconds rather than waiting for the full timeout
- **Idempotent workflow start** — workflow ID is derived from the repo name; calling generate twice returns the existing workflow instead of starting a duplicate run
- **Human-in-the-loop approval** — workflow pauses after document assembly and waits for an approve or reject signal before publishing; times out after 48 hours
- **Provider-agnostic LLM** — all LLM calls go through Spring AI's `ChatClient`; swapping from Gemini to OpenAI or Ollama requires only a config change
- **Payload codec** — large Temporal payloads are transparently offloaded to PostgreSQL to stay within Temporal's 2MB limit
- **Audit trail** — every approved run writes token usage, cost, reviewer, and timestamp to `audit.json` alongside the generated document

---

## How it works

```
POST /api/docs/generate  →  repoUrl
         │
         ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         Temporal Workflow                            │
│                                                                      │
│  Step 1   Clone & inventory all Java files                           │
│  Step 2   Static analysis per module (cyclomatic complexity)         │
│  Step 2b  Load SHA-256 cache — skip unchanged modules                │
│  Step 3   LLM explanation per changed module      ← Spring AI        │
│  Step 4   Architectural analysis across all modules  ← Spring AI     │
│  Step 5   Assemble markdown document              ← Spring AI        │
│  Step 6   Notify reviewer, sleep until signal                        │
│                                                                      │
│        POST /api/docs/{id}/approve  or  /reject                      │
│                                                                      │
│  Step 7   Publish to output/{workflowId}/architecture.md             │
│  Step 8   Save SHA-256 hashes + narratives to PostgreSQL             │
└──────────────────────────────────────────────────────────────────────┘
```

Each step is an independently checkpointed Temporal activity. A crash between any two steps resumes at the next step — not from the beginning.

---

## Stack

| Layer | Technology |
|---|---|
| Workflow engine | [Temporal](https://temporal.io) 1.32.0 |
| Application | Spring Boot 4.0.6 / Java 21 (virtual threads) |
| LLM (default) | Google Gemini `gemini-3.1-flash-lite` via Spring AI 2.0.0-M6 |
| LLM (supported) | OpenAI, Anthropic Claude, Azure OpenAI, Ollama, Mistral, and [more](https://docs.spring.io/spring-ai/reference/api/chatmodel.html) |
| Static analysis | JavaParser |
| Database | PostgreSQL 16 + pgvector |
| Metrics | Micrometer + Prometheus |
| Dashboards | Grafana |

---

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker + Docker Compose
- An API key for your chosen LLM provider (see [Switching providers](#switching-llm-providers) below)

---

## Getting started

### 1. Clone

```bash
git clone https://github.com/jrdesai/checkpoint-ai.git
cd checkpoint-ai
```

### 2. Start infrastructure

```bash
docker compose up -d
```

This starts:
- PostgreSQL with pgvector on port `5432`
- Temporal server on port `7233`
- Temporal Web UI on port `8088`
- Prometheus on port `9090`
- Grafana on port `3000` (admin / admin)

### 3. Set your API key

The default configuration uses Google Gemini:

```bash
export GOOGLE_API_KEY=your_api_key_here
```

Or add it to your run configuration in IntelliJ. See [Switching providers](#switching-llm-providers) to use a different model.

### 4. Run the app

```bash
./mvnw spring-boot:run
```

The app starts on port `8080`.

---

## API reference

### Start a documentation workflow

```bash
curl -X POST http://localhost:8080/api/docs/generate \
  -H "Content-Type: application/json" \
  -d '{"repoUrl": "https://github.com/owner/repo.git"}'
```

Returns a deterministic workflow ID based on the repo name, e.g. `doc-my-repo`. Calling this endpoint again for the same repo while a workflow is running returns the existing workflow ID — no duplicate runs.

You can also pass a local path:

```bash
-d '{"repoUrl": "/path/to/local/java/project"}'
```

### Check status

```bash
curl http://localhost:8080/api/docs/{workflowId}/status
```

```json
{
  "workflowId": "doc-checkpoint-ai",
  "repoName": "checkpoint-ai",
  "status": "EXPLAINING_MODULES"
}
```

Possible statuses: `STARTED` → `CLONING_REPOSITORY` → `ANALYSING_COMPLEXITY` → `EXPLAINING_MODULES` → `ANALYSING_ARCHITECTURE` → `ASSEMBLING_DOCUMENT` → `AWAITING_APPROVAL` → `PUBLISHING` → `COMPLETED` / `REJECTED` / `FAILED`

### Approve

```bash
curl -X POST http://localhost:8080/api/docs/{workflowId}/approve \
  -H "Content-Type: application/json" \
  -d '{"reviewerName": "Your Name", "comments": "Looks good"}'
```

### Reject

```bash
curl -X POST http://localhost:8080/api/docs/{workflowId}/reject \
  -H "Content-Type: application/json" \
  -d '{"reviewerName": "Your Name", "comments": "Needs more detail on the auth module"}'
```

---

## Output

Each approved run produces a dedicated folder:

```
output/documentation/{workflowId}/
├── architecture.md   ← the generated document
└── audit.json        ← token usage, cost, reviewer, timestamp
```

Example `audit.json`:

```json
{
  "workflowId": "doc-checkpoint-ai",
  "repoName": "checkpoint-ai",
  "modulesDocumented": 28,
  "modelUsed": "gemini-3.1-flash-lite",
  "inputTokens": 2231,
  "outputTokens": 784,
  "estimatedCostUsd": 0.000573,
  "reviewerName": "Jigar",
  "approvedAt": "2026-05-18T23:55:24Z",
  "outputPath": "output/documentation/doc-checkpoint-ai/architecture.md"
}
```

---

## Crash recovery demo

Temporal checkpoints every completed activity. To see this in action:

1. Start a workflow against a project with many Java files
2. Watch the logs — when you see `Explaining module: X`, kill the Spring Boot process
3. Open the Temporal UI at `http://localhost:8088` — the workflow shows as **Running**, not Failed
4. Restart the app
5. Watch the logs — completed modules resume instantly with no LLM calls; the workflow continues from the exact point it stopped

Recovery happens within ~30 seconds of restart — the heartbeat timeout ensures Temporal does not wait for the full activity timeout before retrying.

---

## Incremental re-runs

On the first run against a repo all modules are explained by the LLM. On subsequent runs:

- Each file is SHA-256 hashed and compared against the stored hash from the last approved run
- Unchanged files reuse the cached narrative — no LLM call, no cost
- Only new or modified files are sent to the LLM

On a second run against an unchanged codebase you will see `Cache hit X/28` for every module — zero LLM calls for the explain step, completing in seconds instead of minutes.

---

## Project structure

```
src/main/java/.../checkpoint_ai/
├── api/                        REST controllers and DTOs
│   ├── DocumentationController
│   ├── DocumentationService
│   └── dto/
├── activity/                   Temporal activity implementations
│   ├── CodebaseDocumentationActivities       (interface)
│   └── CodebaseDocumentationActivitiesImpl   (implementation)
├── audit/                      Audit trail record
├── codec/                      Temporal payload codec (large payload handling)
├── config/                     Spring beans (AI, Temporal DataConverter)
├── domain/
│   ├── model/                  Domain records (RepositoryInfo, ModuleNarrative, etc.)
│   └── workflow/               Temporal workflow interface + implementation
└── persistence/                JPA entities and repositories (payload store, module cache)
```

---

## Testing

The project includes unit tests covering the core logic and REST layer:

- **`ComplexityAnalyserTest`** — cyclomatic complexity calculation, risk level assignment, and most complex method identification.
- **`DocumentationControllerTest`** — all four REST endpoints, input validation, and error handling using `MockMvc`.

Run all tests:

```bash
./mvnw test
```

---

## Configuration

Key properties in `application.yaml`:

| Property | Default | Description |
|---|---|---|
| `spring.ai.google.genai.chat.options.model` | `gemini-3.1-flash-lite` | LLM model |
| `spring.ai.google.genai.chat.options.temperature` | `0.3` | Response determinism |
| `spring.temporal.connection.target` | `local` | Temporal server address |
| `spring.threads.virtual.enabled` | `true` | Java 21 virtual threads |

---

## Switching LLM providers

All LLM calls go through Spring AI's `ChatClient` abstraction. Swapping the provider requires only a dependency and configuration change — the workflow and activity code stays identical.

### OpenAI (GPT-4o, GPT-4o-mini)

Add to `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```

Add to `application.yaml`:
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o-mini
          temperature: 0.3
```

### Anthropic Claude

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      chat:
        options:
          model: claude-3-5-haiku-20241022
          temperature: 0.3
```

### Ollama (local, no API key required)

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
```

```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      chat:
        options:
          model: llama3.2
```

For the full list of supported providers see the [Spring AI documentation](https://docs.spring.io/spring-ai/reference/api/chatmodel.html).

---

## Observability

- **Health**: `GET /actuator/health`
- **Metrics**: `GET /actuator/metrics`
- **Prometheus**: `GET /actuator/prometheus`
- **Temporal UI**: `http://localhost:8088` — full workflow event history, activity retries, signals
- **Grafana**: `http://localhost:3000` — JVM memory, HTTP request rates, DB connection pool
