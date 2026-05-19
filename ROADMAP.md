# checkpoint-ai Roadmap

This document tracks planned improvements, known gaps, and future feature ideas for the checkpoint-ai project.

---

## In Progress

### Draft preview endpoint
- `GET /api/docs/{workflowId}/draft` — expose the assembled document before approval
- Allows reviewers to read the full markdown content before approving or rejecting
- Implemented via Temporal query handler — no disk writes required

---

## Short Term

### Payload codec threshold
- Raise `SIZE_THRESHOLD_BYTES` from `10_000` (10KB) to `100_000` (100KB) or `256_000` (256KB)
- Current threshold is too aggressive — almost every activity invocation triggers a DB write
- Temporal handles payloads up to 256KB natively without issues

### Payload record cleanup
- `payload_record` entries accumulate indefinitely with no TTL or deletion strategy
- Add a scheduled job to delete payload records older than 7 days
- Only safe to delete records from completed, rejected, or terminated workflows

### Externalise `SIZE_THRESHOLD_BYTES`
- Move the hardcoded threshold in `DatabasePayloadCodec` to `application.yaml`
- Makes it configurable without code changes

### Temp directory cleanup
- `cloneRepository` creates a temp directory via `Files.createTempDirectory("checkpoint-")` with no cleanup
- Add a `cleanupTempDirectory` activity called at the end of the workflow (success, rejection, and timeout paths)
- Prevents disk space accumulation on long-running servers

### `@ControllerAdvice` for global exception handling
- Currently the `@ExceptionHandler` lives inside `DocumentationController`
- Extract to a dedicated `GlobalExceptionHandler` class annotated with `@ControllerAdvice`
- Scales better when more controllers are added

---

## Medium Term

### Audit history endpoint
- Store `AuditRecord` in PostgreSQL after each approved run (in addition to the current `audit.json` file)
- Expose `GET /api/docs/history` returning all approved runs across all repos
- Enables querying total cost, runs per repo, reviewer history

### Additional test coverage
- `DocumentationServiceTest` — verify workflow ID derivation, signal dispatch, 404 handling with mocked Temporal client
- `ModuleCacheTest` — verify cache hits/misses based on hash comparison, orphan cleanup, batch upsert behaviour
- `WorkflowIntegrationTest` — end-to-end workflow test using Temporal's `TestWorkflowEnvironment`

### Cost cap before starting
- After `cloneAndInventory`, estimate the LLM cost: `modules × avg tokens per file × price per token`
- If estimated cost exceeds a configurable threshold (e.g. `$1.00`), reject the workflow before any LLM calls
- Protects against accidentally running against large monorepos

### Workflow metrics in Grafana
- Inject `MeterRegistry` into `DocumentationService` and `CodebaseDocumentationActivitiesImpl`
- Track: workflows started/completed/rejected, cache hit rate, LLM call latency, estimated cost over time
- Build custom Grafana panels on top of the existing Prometheus pipeline

---

## Long Term

### RAG chatbot endpoint
- pgvector is already in the stack but unused
- Generate embeddings for each module narrative after an approved run and store in `vector_store`
- Expose `POST /api/docs/{workflowId}/chat` — accepts a natural language question and returns relevant module context
- Example: *"Which classes handle database transactions?"*

### CI/CD webhook trigger
- `POST /api/docs/trigger` — GitHub webhook endpoint that fires after a merge to main
- Only starts a new workflow if files have changed since the last approved run (use the SHA-256 cache to check)
- Turns documentation from a manual step into an automatic one

### Temporal versioning
- Implement `Workflow.getVersion()` calls for safe evolution of in-flight workflows
- Required when deploying changes to workflow logic while workflows are still running
- Low priority until the project has production traffic or long-running workflows in flight

### Interactive review UI
- Simple web UI (React or Thymeleaf) for reviewers
- Shows list of workflows awaiting approval with status indicators
- Renders the markdown draft in a preview pane before approve/reject
- Eliminates the need for curl commands during the review step

### Multi-language support
- Currently only `.java` files are inventoried and analysed
- Extract a `LanguageAnalyser` interface with separate implementations for Java, Kotlin, and TypeScript
- Makes the tool useful for polyglot repositories

### Deployment
- Deploy to a cloud VM (e.g. AWS EC2 free tier, fly.io, or Railway)
- Containerise the Spring Boot app with a `Dockerfile`
- Update `compose.yaml` to support both local dev and production configurations
- A live URL significantly improves the project's demonstrability

---

## Known Issues

| Issue | Severity | Notes |
|---|---|---|
| Payload records never deleted | Medium | Accumulates indefinitely — add TTL cleanup job |
| Temp cloned repos never deleted | Medium | Disk space leak on repeated runs |
| `SIZE_THRESHOLD_BYTES` too low | Low | Causes excessive DB writes — raise to 100KB+ |
| No auth on API endpoints | Low | Acceptable for local personal use; required before any public deployment |
| LLM output quality varies | Low | Prompts are functional but not optimised; multi-pass review would improve accuracy |
