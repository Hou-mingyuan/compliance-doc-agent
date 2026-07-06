# Security Policy

## Supported Versions

**compliance-doc-agent** is actively maintained on the `main` branch. Security fixes target `main` first.

| Version | Supported |
| --- | --- |
| Latest on `main` | Yes |
| Older tags | Best effort |

## Reporting a Vulnerability

Do **not** open a public issue with exploit details, credentials, API Key, or proof-of-concept code.

Preferred channels:

1. GitHub **Private vulnerability reporting** or a **Security Advisory** for this repository, if enabled.
2. If private reporting is unavailable, open a public issue asking for a private contact channel **without** technical exploit details.

Include affected version/commit, reproduction steps, impact, and relevant logs with secrets removed.

## Scope

### In scope

- Spring Boot REST / SSE APIs (`/api/documents`, `/api/compliance/**`)
- Document upload parsing (PDF / TXT / Markdown) and file size limits
- Docker Compose deployment and default H2 credentials
- LLM gateway configuration and secret handling (`LLM_API_KEY`)
- Mock demo data leakage or misconfiguration in production
- CORS defaults (`allowed-origins: *` in dev)

### Out of scope (current MVP)

- End-user / auditor RBAC and SSO (see README Roadmap Phase 3)
- Multi-tenant data isolation
- Third-party LLM provider security posture
- Built-in regulation corpus authenticity (all sample rules/text are fictional)

## Secret Handling

This project follows a **bring-your-own-key (BYOK)** model for real LLM usage:

- Default `LLM_PROVIDER=mock` requires **no** API Key.
- Never commit `.env`, `LLM_API_KEY`, or uploaded customer documents.
- `config.json` / `.env.example` contain placeholders only.

## Production Baseline

- **Mock LLM is for local demo / CI only** — disable in production unless intentionally isolated.
- Override default H2 / MySQL credentials; do not expose H2 console (`/h2-console`) publicly.
- Set `LLM_API_KEY` via secrets manager; rotate keys on staff turnover.
- Place HTTPS termination, authentication, and rate limiting at a reverse proxy or API gateway.
- Restrict upload MIME types and scan uploads for malware at the gateway layer.
- Narrow CORS to known frontend origins in production.
- Disable Spring H2 console in production profiles.

See [DEPLOYMENT.md](DEPLOYMENT.md) for deployment hardening and [docs/USAGE.md](docs/USAGE.md) for safe local demo setup.

## Document Upload Safety

- MVP enforces single-file size limit (≤ 5MB) at the controller layer.
- Parsed content is stored in H2/MySQL; treat the database as sensitive in production.
- Sample files under `backend/src/main/resources/samples/` are fictional — do not use as real legal advice.

## Audit Trail

MVP persists audit events for workflow state changes. Production deployments should:

- Restrict database access to application service accounts.
- Back up audit tables with the same retention policy as source documents.
- Plan RBAC before exposing the API beyond trusted networks (Roadmap Phase 3).
