# Release readiness refresh — 2026-08-03

## Scope and baseline

- Active checkout: `C:\Users\admin\Downloads\hotelmanagement-new`.
- Working branch: `fix/release-readiness-20260802`.
- Baseline before this refresh: `d7b876af19f797f1ceba6a18e83a4ff80381ee12`.
- PostgreSQL is the only supported runtime database.
- No production database row, payment ledger entry, secret or Docker volume was
  deleted or rewritten during this refresh.

This document records evidence for the current working tree. Historical reports
remain evidence only for the revisions on which they were produced.

## Verified automated gates

| Gate | Current result |
| --- | --- |
| Backend unit suite | PASS — 523 tests, 0 failures/errors/skips |
| PostgreSQL/Flyway integration suite | PASS — 27 tests, 0 failures/errors/skips |
| Fresh and legacy PostgreSQL 16 migration | PASS — V1 through V33 plus Hibernate schema validation |
| Local retained database migration | PASS — V33 applied; zero public `NOT VALID` constraints remain |
| PostgreSQL idempotency/concurrency | PASS — one financial side effect survives concurrent duplicate submissions |
| Frontend ESLint | PASS — no warnings/errors |
| Frontend TypeScript | PASS — `tsc --noEmit` |
| Frontend unit suite | PASS — 75 tests in 17 files |
| Frontend production build | PASS — 47 routes generated |
| Diff hygiene | PASS — no whitespace errors; Windows LF/CRLF notices are informational |

## Findings closed

### Release and repository protection

- GitHub ruleset `Long-lived branch protection` targets `main` and `develop`.
- It blocks branch deletion and force pushes, requires a pull request, and
  requires status checks to pass before merge. No bypass actor is configured.
- GitHub Dependency Graph, Dependabot alerts, malware alerts and Dependabot
  security updates are enabled.
- CodeQL default setup is enabled for GitHub Actions, Java/Kotlin and
  JavaScript/TypeScript. The first complete scan must finish before making its
  result a required merge check, otherwise all merges could be blocked without
  an established baseline.
- GitHub private vulnerability reporting is enabled and `SECURITY.md` defines
  the private disclosure path.
- Vercel Production and Preview both have `Lint` and `Typecheck` Deployment
  Checks configured. Production builds are prioritized.

### Authentication topology

- The browser calls the backend through the first-party `/backend_proxy` path.
  The production refresh-cookie default `Secure` + `SameSite=Lax` is therefore
  intentional and consistent with the deployed topology.
- `SameSite=None` is only appropriate if the browser is deliberately changed to
  call Render cross-site, followed by a complete login/refresh/logout and OAuth
  retest.
- The in-memory authentication limiter is acceptable only while Render runs a
  single application instance. Shared storage is required before horizontal
  scaling.

### Booking, selected-stay pricing and date UX

- A clean production browser run proved the pricing quote endpoint and same-
  origin proxy return an authoritative quote and enable booking; the reported
  quote blocker was stale for the audited production revision.
- Invalid checkout-before-check-in input already produces an explicit inline
  error, clears stale availability and preserves a valid editable form state.
- Public room catalog cards keep the comparable overnight rate. Availability
  cards now show the authoritative estimate for the selected stay, including
  hourly/overnight/daily package and duration, so a two-hour search is no longer
  mislabeled as an overnight price.
- Booking submit intentionally remains clickable before accepting terms so the
  requested message `Bạn phải đồng ý với Điều khoản & Điều kiện trước khi đặt
  phòng` can be shown; the handler still prevents reservation creation.

### Chatbot reliability

- Production reproduced a fallback when the chatbot tried to fetch hotel data
  through HTTP back into the same small Render process.
- Public room, facility, gallery and availability reads now use an injected,
  read-only in-process gateway. Gemini remains the only external WebClient call.
- Deterministic room-catalog questions no longer depend on Gemini and have a
  focused regression test.

### SEO and deployment URL

- Canonical and Open Graph URLs now derive from `NEXT_PUBLIC_SITE_URL`, then
  Vercel production/runtime URLs, with localhost only as a local fallback.
- Root and public content routes provide canonical metadata, including dynamic
  room-detail routes.
- Production verification is required after deployment because the currently
  deployed revision predates these fixes.

### PostgreSQL cutover completion

- V33 validates every staged legacy check from V2, V4 and V19.
- Operational foreign-key indexes were added for work scheduling, cash
  movements, checkout reconciliation and pricing quote lines.
- Migration tests assert V33, the new indexes and zero remaining public
  unvalidated constraints on both fresh and legacy fixtures.
- The post-cutover validation script now covers the same constraints as Flyway.

### Project metadata

- Maven project, SCM, developer and proprietary-license metadata are explicit;
  no open-source license was invented.
- `SECURITY.md` forbids public disclosure of secrets/PII and directs reports to
  GitHub private security advisories.

## Remaining external or conditional gates

| Area | Status | Evidence still required |
| --- | --- | --- |
| Production deployment | PENDING | Merge this revision, wait for GitHub/Vercel/Render gates, then repeat browser smoke checks |
| CodeQL baseline | PENDING | Initial default-setup scan completes without unresolved high-severity finding |
| Full English localization | PARTIAL | Language-specific server metadata and complete English copy require locale-aware routes or equivalent server routing |
| CSP nonce architecture | PARTIAL | Remove `unsafe-inline` only through a tested nonce/hash rollout; deleting it directly would break current Next.js rendering |
| Render Free latency | ACCEPTED LIMITATION | Monitoring reduces idle gaps but does not provide a paid SLA or eliminate all cold starts |
| Multi-instance rate limit | CONDITIONAL | Replace in-memory state with a shared store before adding a second backend instance |
| SendGrid | EXTERNAL | Verify production inbox delivery and high-risk alert receipt |
| SePay real bank | EXTERNAL | Coordinate one incoming and one outgoing/refund transfer, then reconcile webhook, ledger and reservation |
| Load/capacity | MISSING EVIDENCE | Sustained test on an approved staging/Neon clone with latency, error and resource thresholds |
| DR | PARTIAL | Current Neon restore clone, validation SQL, measured RPO/RTO and rollback rehearsal |
| Operator UAT | EXTERNAL | STAFF/ADMIN sign-off for reservation, payment/refund, check-in, reconciliation and checkout |
| Legal/privacy review | EXTERNAL | Owner/legal review of public policy text and data-retention obligations |

## Release conclusion

The code-level candidate is green for unit tests, frontend static gates,
PostgreSQL migrations and targeted concurrency controls. Repository and Vercel
quality gates are configured rather than merely documented. The revision is
eligible for controlled deployment and production smoke verification, but it is
not honest to declare unconditional financial go-live until the real-provider,
load, DR and operator evidence above is recorded.
