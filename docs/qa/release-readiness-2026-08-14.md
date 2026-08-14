# Release readiness evidence — 2026-08-14

This file is the current point-in-time release verification for the active
checkout. Older QA and handoff reports are retained as historical evidence and
must not be used as current test counts.

## Audited source

- Local ref before remediation: `272e38a5b77e10f781adb245cf2fe6d6357f6640`.
- Production ref observed during the audit:
  `62db4a7c304a62b5ed72fb12974f8bbcd8d771aa`.
- The two refs had the same Git tree, so the deployed source content matched
  the audited checkout before the dependency-only remediation.

## Automated gates

| Gate | Result |
|---|---|
| Backend full verification | PASS — 710 tests, 0 failure/error/skip |
| PostgreSQL migration profile | PASS — PostgreSQL 16, Flyway V1–V39 |
| Frontend production dependency audit | PASS — no known vulnerabilities |
| Frontend ESLint | PASS |
| Frontend TypeScript | PASS |
| Frontend unit tests | PASS — 105 tests in 20 files |
| Frontend production build | PASS — 47 routes |

The PostgreSQL concurrency test intentionally exercises duplicate inserts
against the idempotency unique constraint. Duplicate-key log entries in that
test are expected evidence of the losing concurrent requests and are not test
failures.

## Production data checks

Read-only Neon SQL checks during the audit confirmed:

- Flyway latest successful migration: V39;
- 95 foreign keys, all validated;
- zero unvalidated foreign keys and zero unvalidated check constraints;
- zero orphan rows across the audited foreign keys;
- 45 owned sequences and zero sequences behind their table maximum IDs.

## Remediation included after the audit

- Updated the enforced `nanoid` transitive version from 3.3.17 to 3.3.18.
- Regenerated the pnpm lockfile.
- Re-ran dependency audit, lint, typecheck, all frontend tests, frontend build,
  backend tests and PostgreSQL migration gates successfully.

This dependency remediation changes neither REST/database contracts nor any
reservation, pricing, payment, refund, RoomHold, check-in or checkout workflow.

## Open production gates

The following items cannot be marked complete from source-code tests alone:

1. Rotate or disable the existing production ADMIN account that still accepts
   a demo password, then revoke existing privileged sessions.
2. Complete an isolated STAFF operator UAT covering reservation operations,
   cash collection, check-in, reconciliation and checkout.
3. Capture one controlled real-provider SePay incoming/outgoing/refund cycle
   and SendGrid delivery/alert evidence.
4. Rehearse a Neon restore and record accepted RPO/RTO.
5. Establish sustained load/capacity evidence. The current Render/Neon free
   tiers are suitable for demo/UAT or accepted low volume, not an SLA-backed
   production service.
6. Record a deliberate decision for Render cold starts versus paid always-on
   capacity. Keep-alive requests do not provide an availability guarantee.

## Release disposition

- Code-level candidate: **PASS**.
- Production demo/UAT: **READY WITH CONTROLLED ACCESS**.
- Unrestricted real-money go-live: **BLOCKED** until the privileged credential,
  provider/UAT, restore and capacity gates above have evidence.
