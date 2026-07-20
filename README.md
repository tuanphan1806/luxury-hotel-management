# Luxury Hotel Management

[![Production gates](https://github.com/tuanphan1806/luxury-hotel-management/actions/workflows/ci.yml/badge.svg)](https://github.com/tuanphan1806/luxury-hotel-management/actions/workflows/ci.yml)

A full-stack hotel reservation and operations platform with multi-room bookings, room holds, SePay QR payments, automated reconciliation, refunds, check-in/check-out workflows, immutable audit trails, and role-based dashboards.

## Architecture

| Layer | Technology |
| --- | --- |
| Guest and operations UI | Next.js 15, React 19, TypeScript, Tailwind CSS |
| Backend API | Java 17, Spring Boot 3.5, Spring Security, JPA |
| Database | PostgreSQL 16, Flyway migrations |
| Payments | SePay incoming/outgoing webhooks and reconciliation |
| Media | Cloudinary with local/S3-compatible storage adapters |
| Delivery | GitHub Actions, Vercel, Render, Neon |

## Core workflows

- Book multiple room types and quantities in one reservation.
- Create a `RoomHold` only when a deposit QR is issued.
- Pay a 50% deposit or the full reservation amount through SePay QR.
- Protect payment mutations with idempotency keys and webhook replay checks.
- Match incoming and outgoing bank transfers to the payment/refund ledger.
- Assign concrete rooms by room type during check-in without double assignment.
- Reconcile all charges and payments before checkout.
- Handle bank and cash refunds through a single completion boundary.
- Record high-risk staff/admin actions in an append-only audit trail.

## Repository layout

```text
.
|-- code/
|   |-- backend/       Spring Boot API, Flyway migrations and tests
|   `-- frontend/      Next.js guest site and operations dashboard
|-- docs/              Architecture, payment platform and deployment notes
|-- .github/workflows/ CI production gates
`-- render.yaml        Render backend blueprint
```

## Local development

Prerequisites:

- Java 17+
- Docker Desktop
- Node.js 22 or 24
- pnpm 11

Create local configuration files from the committed templates:

```powershell
Copy-Item code/backend/.env.example code/backend/.env
Copy-Item code/frontend/.env.example code/frontend/.env.local
```

Start PostgreSQL and the backend:

```powershell
Set-Location code/backend
docker compose up -d postgres
.\mvnw.cmd spring-boot:run
```

Start the frontend in another terminal:

```powershell
Set-Location code/frontend
pnpm install --frozen-lockfile
pnpm run dev
```

The frontend runs at `http://localhost:3000`; the backend runs at `http://localhost:8080`.

Demo users are available only when the development seed flags are enabled. Production configuration disables demo users and requires a one-time secure admin bootstrap.

## Verification

Backend unit and integration tests:

```powershell
Set-Location code/backend
.\mvnw.cmd test
```

PostgreSQL migration and concurrency gates:

```powershell
.\mvnw.cmd -Ppostgres-migration-test verify
```

Frontend quality gates:

```powershell
Set-Location ../frontend
pnpm run lint
pnpm run build
```

The same gates run automatically in GitHub Actions for pushes and pull requests involving `develop` or `main`. Pull requests also validate the permitted promotion path.

## Git workflow

The repository uses two long-lived branches:

- `develop` integrates short-lived `feature/*`, `fix/*`, and maintenance branches.
- `main` contains only releases approved for the live production environment.

Normal changes move through `feature/* -> develop -> main`. Urgent live fixes use `hotfix/*` from `main` and must be merged into both `main` and `develop`. See [CONTRIBUTING.md](CONTRIBUTING.md) for naming, pull-request, verification, release, and rollback rules.

## Deployment

The free-tier reference topology is:

- Frontend: Vercel
- Backend: Render (Docker)
- Database: Neon PostgreSQL 16 in Singapore
- Media: Cloudinary

Configure development/UAT and staging validation from `develop`; deploy the live environment only from `main` or a release tag created from `main`. Preview deployments may be created for pull requests, but must never use production credentials.

Follow [the production deployment runbook](docs/deployment/PRODUCTION_DEPLOYMENT.md) for environment variables, bootstrapping, SePay webhook configuration, health checks, and rollback.

> Render Free can sleep while idle. It is suitable for demos and UAT, but a continuously available paid backend is required before accepting real production payments.

## Security

- Never commit `.env`, database credentials, provider secrets, private keys, or webhook secrets.
- Rotate any credential exposed in chat, logs, screenshots, or Git history before deployment.
- Keep demo seeding disabled in production.
- Use a unique SePay HMAC secret and verify both timestamp tolerance and replay protection.
- Restrict audit and override endpoints to their documented roles.

## Documentation

- [Production deployment](docs/deployment/PRODUCTION_DEPLOYMENT.md)
- [Payment platform](docs/payment-platform/)
- [Database ERD](docs/DB-ERD/)
- [API summary](API_SUMMARY.md)

This repository is maintained as an academic and portfolio project. No open-source license is granted unless a license file is added explicitly.
