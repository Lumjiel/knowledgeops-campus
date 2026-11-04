# API Recipes

<!--
  Document header: copy-paste curl recipes for the KnowledgeOps REST surface,
  grouped by capability (auth, chat, ingestion, RAG, agent, history/audit,
  health). Every command assumes the local Docker Compose stack and the seeded
  demo credentials from `.env.example`. Commands are executable as-is; prose
  between blocks only explains when to use each endpoint.
-->

These recipes assume the local Docker Compose stack is running on `http://localhost:8080`.

## Common Headers

Shared auth/tenant headers used by every recipe below. Set these once per shell; the token-exchange call at the end of this section is only needed for JWT-based endpoints.

```bash
export BASE_URL=http://localhost:8080
export API_KEY=<local-demo-api-key>
export TENANT_ID=default
```

Most local examples can use the seeded API key:

```bash
-H "X-API-Key: $API_KEY" -H "X-Tenant-Id: $TENANT_ID"
```

When security is enabled, the API key or JWT tenant is authoritative and the
tenant header cannot switch authorization scope. The header remains in these
recipes for local demo correlation and must match the API key during token exchange.

For JWT-based calls, set `API_KEY` to the seeded development value from `.env.example`, then exchange the API key first:

```bash
curl -X POST "$BASE_URL/auth/token" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## Chat

Plain knowledge-base chat over a GET query string. Append one of the optional `modelProfile` values to trade cost for answer quality.

```bash
curl "$BASE_URL/ai/chat?prompt=Summarize%20KnowledgeOps%20Campus&chatId=demo-chat" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

Optional query parameters:

- `modelProfile=economy`
- `modelProfile=balanced`
- `modelProfile=quality`

## Upload a PDF for Ingestion

Asynchronous pipeline: the upload returns immediately with a `jobId`, which you then poll (or list recent jobs per chat). The `X-Idempotency-Key` makes retries of the same upload safe.

```bash
curl -X POST "$BASE_URL/ingestion/upload/demo-rag" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -H "X-Idempotency-Key: demo-rag-001" \
  -F "file=@demo-data/heat-safety-policy.pdf"
```

Check the returned `jobId`:

```bash
curl "$BASE_URL/ingestion/jobs/<jobId>" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

List recent ingestion jobs for a chat:

```bash
curl "$BASE_URL/ingestion/jobs?chatId=demo-rag&limit=20" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## Ask the PDF RAG Endpoint

Question answering restricted to ingested PDF content. Run this after the ingestion job for `demo-rag` has succeeded, or the answer will have no sources to cite.

```bash
curl "$BASE_URL/ai/pdf/chat?prompt=What%20are%20the%20key%20points%3F&chatId=demo-rag" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

The response includes answer text and citation lines when matching sources are available.

## ReAct Agent

Tool-calling agent in two flavors: the blocking endpoint returns the full JSON trace, while the `/stream` variant emits server-sent events for incremental output (`curl -N` disables buffering).

JSON response:

```bash
curl -X POST "$BASE_URL/ai/react/chat" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"prompt":"Find the next useful operations check","chatId":"react-demo","modelProfile":"balanced"}'
```

SSE stream:

```bash
curl -N -X POST "$BASE_URL/ai/react/chat/stream" \
  -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID" \
  -d '{"prompt":"Explain the ingestion pipeline","chatId":"react-stream-demo"}'
```

## History and Audit

Read-side verification: chat history shows what the assistant stored per conversation, and audit logs show the corresponding server-side action trail.

```bash
curl "$BASE_URL/ai/history/chat" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"

curl "$BASE_URL/audit/logs" \
  -H "X-API-Key: $API_KEY" \
  -H "X-Tenant-Id: $TENANT_ID"
```

## Health and Observability

Unauthenticated Spring Boot Actuator endpoints for liveness checks and Prometheus scraping — useful as the first smoke test after `docker compose up`.

```bash
curl "$BASE_URL/actuator/health"
curl "$BASE_URL/actuator/prometheus"
```

For logs, traces, alerting, and drill workflows, continue with [Operations Manual](operations.md).
