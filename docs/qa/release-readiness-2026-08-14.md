# Release readiness evidence — 2026-08-14

This is the current release record for the active checkout. Older QA reports
remain historical evidence; their test counts and deployment refs must not be
treated as the current state.

## Audited source and release boundary

- Active checkout: `C:\Users\admin\Downloads\hotelmanagement-new`.
- Remediation branch: `chore/go-live-hardening-20260814`.
- Baseline ref: `8ceccaa8a2dfcad9d58fe067e0790891963ef37f`.
- Latest production main before this remediation:
  `57358c4e461482108fe07add06b2d43d2ae42ffc`.
- Scope: non-destructive source, migration, security, accessibility,
  performance-query and release-gate hardening. No reservation, RoomHold,
  pricing, payment, refund, ledger, check-in or checkout state transition was
  reordered or bypassed.

It is not technically possible to prove that a non-trivial application has no
unknown bug or that every source line is correct. The release conclusion below
is based on repeatable static, unit, integration, migration, concurrency,
browser and production-build evidence instead of that unverifiable claim.

## Current automated evidence

| Gate | Result |
|---|---|
| Backend full verification | PASS — 717 tests, 0 failure/error/skip |
| Backend coverage | PASS — 71.03% lines, 53.02% branches; enforced minimum 65%/45% |
| PostgreSQL migration profile | PASS — PostgreSQL 16, Flyway V1–V39, Hibernate validation |
| Backend SBOM | PASS — CycloneDX 1.6 JSON/XML, 176 components |
| Frontend dependency audit | PASS — no known high-severity advisory |
| Frontend ESLint and TypeScript | PASS |
| Frontend unit tests | PASS — 108 tests in 21 files |
| Frontend deterministic-library coverage | PASS — 78.64% lines, 79.90% branches; minimum 70% |
| Frontend production build | PASS — 47 routes |
| Public accessibility smoke | PASS — 10 desktop/mobile scenarios; no serious/critical axe violation |
| Chatbot browser regression | PASS — 6 desktop/mobile scenarios covering context, privacy and stale requests |
| Docker Compose validation | PASS — normalized configuration is valid |
| Working-tree whitespace gate | PASS |

The PostgreSQL concurrency tests intentionally produce duplicate-key log
entries for losing requests. Those logs are expected evidence that database
uniqueness prevents duplicate financial/idempotency effects.

## Hardening delivered in this candidate

### Supply chain and quality gates

- CI now enforces backend JaCoCo thresholds and frontend V8 coverage
  thresholds instead of only reporting raw test counts.
- CI generates and retains CycloneDX JSON/XML SBOM evidence.
- Pull requests reject newly introduced High/Critical vulnerable dependencies
  and AGPL dependencies through dependency review.
- A full OSV source scan covers the Maven POM and pnpm lockfile on CI; known
  vulnerabilities fail the job and are published as code-scanning evidence.
- The built backend image is scanned for fixable High/Critical operating-system
  and library CVEs before a release check can pass. The final local release
  image returned `0` findings for both Ubuntu and the application JAR after
  upgrading Netty to `4.1.136.Final`, HttpCore to `5.4.3`, and PostgreSQL JDBC
  to `42.7.12`.
- Full frontend dependency audit includes development tooling, not only
  production dependencies.

### Accessibility and browser data protection

- Fixed public-site WCAG contrast issues without changing the Navy & Gold
  design system.
- Added desktop and mobile axe smoke coverage for home, rooms, facilities,
  login and signup.
- Added regression tests that guest reservation tokens remain session-only,
  legacy local persistence is cleaned up and idempotency keys rotate only when
  a completed operation requires a new command.

### Operations performance and financial safety

- The operations attention queue no longer materializes all historical
  reservations. PostgreSQL now returns only statuses/time windows capable of
  producing an attention item.
- Checkout reconciliation characterization coverage now includes replay,
  immutable snapshot, reject, already-closed, no-pending and no-financial-
  rewrite cases.
- The optimization does not alter reservation workflow or financial totals.

## Current readiness matrix

| Area | Status | Evidence / boundary |
|---|---|---|
| Reservation, RoomHold, pricing, check-in/out | COMPLETED (automated) | Unit, integration, migration and concurrency gates pass; operator UAT remains external evidence |
| Payment, refund, ledger, SePay | COMPLETED (code regression) / PARTIAL (real provider) | Idempotency, provider time, reconciliation and failure tests pass; one real-bank matrix is not simulated as proof |
| Backend build, test and coverage | COMPLETED | 717 tests plus enforced coverage |
| Frontend lint, typecheck, unit, build | COMPLETED | All local release gates pass |
| PostgreSQL schema/migration | COMPLETED | Fresh PostgreSQL 16 and V1–V39 migration gate passes |
| Dependency/container security and SBOM | COMPLETED (configured) | pnpm audit clean; dependency review, OSV, CycloneDX and Trivy image scanning are CI gates |
| Accessibility | COMPLETED for automated public smoke / PARTIAL for manual assistive-tech audit | Axe desktop/mobile passes; full screen-reader operator audit is human evidence |
| CI and branch policy | COMPLETED after the new checks are required on protected branches | Verify the first PR checks and ruleset before promotion |
| Production credentials | ACCEPTED FOR CONTROLLED TESTING | Owner explicitly keeps demo credentials until public go-live; rotation remains mandatory before public access |
| Capacity/cold start on Free tiers | DEFERRED BY OWNER | No SLA claim; do not run destructive/stress load against shared production |
| Distributed rate limiting | NOT REQUIRED for the current single Render instance | Mandatory before horizontal scaling or multi-instance deployment |
| CSP nonce/hash migration | STAGED HARDENING | Current CSP retains `unsafe-inline`; nonce conversion requires request-time rendering and a full Next.js/auth performance regression |
| Neon restore/RPO/RTO | PARTIAL | Earlier restore evidence expired; repeat immediately before real public go-live on the chosen paid/free retention topology |
| STAFF operator UAT | PARTIAL | Automated workflow coverage is strong; a human shift/reservation/payment/check-out sign-off is still required |
| Real SePay/SendGrid evidence | PARTIAL | Must be captured with real provider accounts; source tests cannot replace bank/inbox evidence |
| Analytics/funnel | DEFERRED | Product analytics is useful but is not a correctness gate for the controlled hotel demo |
| Multi-tenancy | N/A | System intentionally models one hotel |

## Deliberate non-changes

- Large orchestrators (`ReservationServiceImpl`, `ChatBotService`,
  `PaymentRefundService`) were not structurally rewritten before release.
  Their behavior is financially sensitive; characterization coverage must lead
  each later extraction.
- The in-memory authentication limiter remains appropriate for exactly one
  backend instance. A Redis/database-backed limiter is a topology change, not
  a safe cosmetic refactor.
- CSP was not converted blindly to a nonce. Next.js nonce propagation changes
  static/dynamic rendering behavior and must be handled as a separately
  benchmarked migration.
- Demo users were not disabled because the owner explicitly retained them for
  controlled production testing. They are a public-go-live blocker, not a
  hidden completed item.

## Mandatory checklist before unrestricted public real-money go-live

1. Disable/rotate all demo credentials and revoke existing privileged
   sessions.
2. Complete one STAFF operator UAT: scheduled shift, check-in, cash collection,
   reservation confirmation, room assignment, guest check-in, reconciliation,
   checkout and audit verification.
3. Capture one controlled SePay matrix: incoming success, underpayment,
   overpayment, duplicate transfer, final payment, outgoing refund and ledger
   reconciliation. Never manufacture provider evidence in the database.
4. Confirm SendGrid verification and alert delivery in the intended inbox.
5. Rehearse Neon restore, run post-cutover SQL validation and record measured
   RPO/RTO.
6. Run bounded staging load/soak and provider/database failure drills against a
   disposable clone, not shared production.
7. Decide paid always-on/capacity and distributed-rate-limit topology if the
   service will carry an uptime or concurrency SLA.

## Release disposition

- Code-level release candidate: **PASS**.
- Controlled demo/UAT on the current Free topology: **READY**.
- Unrestricted public real-money go-live: **CONDITIONAL** on the seven external
  checklist items above.
