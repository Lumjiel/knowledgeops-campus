# Interview Notes

## One-Minute Pitch

KnowledgeOps Campus is a campus-regulation RAG + ReAct assistant that turns a few hundred pages of rules into a governed, deployable, and measurable retrieval system with tenant isolation, asynchronous ingestion, audit logs, observability, and regression evaluation.

## What It Proves

- RAG is implemented as a platform path rather than a single retrieval endpoint.
- Ingestion is asynchronous and observable, with job status, retries, and failure handling.
- Tenant and permission boundaries are visible through API keys, JWT, RBAC, tenant headers, and audit logs.
- The system includes operational proof: Docker Compose, Flyway migrations, metrics, logs, traces, and alerting assets.
- Quality checks are part of the story through unit tests, integration tests, JaCoCo, regression scripts, and E2E smoke evidence.

## Best Technical Story

The strongest story is that the RAG answer is only one surface of the platform. The more important engineering work is the lifecycle around it: document upload, async parsing, vector indexing, tenant-scoped retrieval, answer citations, audit trail, metrics, and regression evaluation.

## Tradeoffs To Explain

- Spring AI runs on the `1.1.7` stable line (migrated from `1.0.0-M6`); treat any further framework upgrade as an explicit compatibility project, not a casual dependency bump.
- Local demo settings prioritize reproducibility over production hardening.
- Some provider integrations are designed to be swappable, so provider-specific behavior should stay behind configuration and adapter boundaries.

## Validation Path

```bash
./scripts/demo.sh
make demo-verify
mvn test
cd frontend && npm ci && npm run lint && npm run build
```

## Follow-Up Ideas

- Add a public regression report artifact with sample questions and expected citation behavior.
- Add a threat model for tenant isolation and API-key/JWT boundaries.
- Publish OpenAPI output as a generated artifact in CI.
