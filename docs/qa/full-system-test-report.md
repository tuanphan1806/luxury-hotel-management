# Full-system QA report

> Current verification note (2026-08-14): the latest automated counts and
> release-gate disposition are in
> `docs/qa/release-readiness-2026-08-14.md` (717 backend tests, 108 frontend
> unit tests, coverage/SBOM/accessibility gates and 47 frontend routes). Counts
> below are retained as evidence for the 2026-07-26/30 audited revision, not as
> the current HEAD inventory.

Status: AUTOMATED QA + PRODUCTION CASH UAT + NEON RESTORE COMPLETE; REAL-BANK/LOAD/EMAIL GATES PARTIAL
Baseline date: 2026-07-26
Audited branch/ref: `release/2026-07-26-system-hardening`, based on
`main` / `476f9c656c86af234706acbfff116530e0d5b7e3`

## Production verification update — 2026-07-30

- Deployed frontend ref: `87e9b3c` (PR #36). GitHub quality gates and the
  Vercel production deployment passed.
- Pricing V2 is enabled in the production blueprint for all six canonical room
  type codes: `STANDARD`, `DELUXE`, `EXECUTIVE`, `SUITE`, `FAMILY` and
  `PRESIDENTIAL`.
- The ADMIN finance dashboard reports **no unmatched incoming or unclassified
  outgoing cash-flow exception** in the active reporting window. The earlier
  2,000 VND SePay review item is no longer present in the live exception queue;
  normal SePay and refund entries remain visible in the read-only journal.
- The checkout-exception queue contains zero pending requests.
- Production cash operator UAT is evidenced by reservation `RES-18557480`:
  atomic walk-in/check-in, 70,000 VND CASH payment, MATCHED checkout,
  immutable invoice `INV-RES-18557480`, `CASH_IN` and
  `REVENUE_RECOGNIZED` journal rows, plus STAFF-attributed audit entries all
  agree on 70,000 VND.
- Authorization UAT after PR #36 confirms STAFF cannot see reservation history,
  while ADMIN can; the dashboard greeting also resolves the authoritative
  server role instead of stale browser storage.
- A bounded read-only production smoke (8 requests per target, concurrency 4)
  returned 24/24 HTTP 200. Observed p95: home 1,625 ms, rooms 560 ms and proxied
  backend health 278 ms. This is a safe latency smoke, not a replacement for a
  sustained production-like load test.
- Neon restore rehearsal completed on the expiring child branch
  `pre-go-live-20260730` (parent `production`, PostgreSQL 16, Singapore). Neon
  successfully reset the clone from the latest production state; production
  itself was not mutated. `post-cutover-validate.sql` then completed all ten
  statements without error: generic FK and identity-sequence checks passed,
  four deferred constraints validated, and all 28 Flyway migrations reported
  success. The restored clone contained 23 reservations across six lifecycle
  states, 19 successful payment rows totalling 8,350,000 VND and three
  successful refunds totalling 475,000 VND. The disposable clone is configured
  to auto-delete on 2026-07-31 at 00:42 GMT+7.

Still external/partial after this update: one coordinated real-bank SePay
incoming/outgoing run, sustained load/concurrency at an approved staging
volume, monitoring-email receipt and final operator sign-off.

## 1. Baseline identity and safety

- Active checkout: `C:\Users\admin\Downloads\hotelmanagement-new`.
- The worktree was already dirty from the approved SOLID refactor. QA preserved
  all existing tracked and untracked changes.
- No reset, checkout, stash, migration repair or production data mutation was
  performed.
- `git diff --check` passes. Line-ending warnings are informational Windows
  LF/CRLF notices, not whitespace errors.

## 2. Runtime topology used for local QA

| Component | Runtime | Evidence | Result |
| --- | --- | --- | --- |
| PostgreSQL | Docker only: `postgres:16-alpine`, host `5433` → container `5432` | Compose healthcheck | HEALTHY |
| Backend | Native Maven/Spring Boot, port `8080` | `/actuator/health` and startup log | HTTP 200 / `UP` |
| Frontend | Native Next.js, port `3000` | `/login` and Playwright | HTTP 200 |
| Docker backend/frontend | Disabled for this QA run | `docker compose ps -a` | STOPPED |

This topology intentionally keeps only PostgreSQL in Docker to reduce local
CPU/RAM usage. Testcontainers starts disposable PostgreSQL containers only for
the migration/concurrency suite.

## 3. Final automated evidence

| Gate | Command / method | Result |
| --- | --- | --- |
| Backend full suite | `.\mvnw.cmd -Ppostgres-migration-test verify` (unit/integration phase) | PASS — 321 tests, 0 failures, 0 errors, 0 skipped |
| Scheduler regression | Included in backend suite | PASS — 9 scenarios |
| Monitoring regression | Included in backend suite | PASS — 3 scenarios |
| PostgreSQL migration suite | `.\mvnw.cmd -Ppostgres-migration-test failsafe:integration-test failsafe:verify` | PASS — 8 tests |
| Flyway fresh database | PostgreSQL 16 Testcontainers, V1→V11 | PASS |
| Flyway compatible legacy paths | PostgreSQL 16 Testcontainers | PASS |
| PostgreSQL idempotency race | Concurrent retries against the unique scope constraint | PASS — one effect |
| Frontend ESLint | project ESLint binary | PASS |
| Frontend unit tests | Vitest | PASS — 9 tests |
| Browser E2E | Playwright Chrome, one worker | PASS — 16 passed, 8 intentional mobile-stateful skips |
| Frontend production build | clean native Windows `next build` | PASS — compile, typecheck, 42 routes, `.next/BUILD_ID` |
| Diff hygiene | `git diff --check` | PASS |

The PostgreSQL `23505` duplicate-key messages are expected contention evidence
from the idempotency test. The `next-intl` build-dependency cache warning does
not fail compilation, type checking or artifact generation.

## 4. Workflows exercised by automated tests

- Registration/login validation, email-verification contract, refresh/logout,
  password reset, ADMIN/STAFF single-device sessions and CUSTOMER access.
- ADMIN/STAFF/CUSTOMER authorization, STAFF audit denial and ADMIN-only audit
  history.
- Facility, room-type and gallery CRUD with media ownership and audit logging.
- Walk-in atomic creation, CASH settlement, fee replacement, check-out and
  retry idempotency.
- Online deposit 50% and prepay 100%, RoomHold creation/conversion and staff
  confirmation ordering.
- Signed SePay incoming webhook, underpayment cancellation/release,
  overpayment excess-only refund, outgoing webhook completion and replay
  protection.
- PostgreSQL fresh/legacy migration, append-only audit constraints and
  concurrent idempotency.
- Scheduler cleanup/expiry/reconciliation behavior and business-monitoring
  aggregation/threshold behavior.
- Public desktop/mobile routes, public catalogue APIs, form validation and
  protected-route redirects.

## 5. Live provider evidence

| Provider flow | Result | Scope |
| --- | --- | --- |
| Google OAuth local | PASS | Real Google login created/returned the correct CUSTOMER account |
| Google OAuth production | PASS | Real login completed on the deployed frontend/backend |
| Facebook OAuth local | PASS | Missing-email completion flow sent verification and reached the confirmation screen |
| Facebook OAuth production | PARTIAL | Production provider redirect/callback configuration verified; final new-user completion was not repeated |
| SendGrid API | PARTIAL | Send accepted with HTTP 202 and arrived, but Gmail classified it as Spam |
| SePay production auth boundary | PASS | Forged/unauthenticated webhook returned HTTP 401 with no ledger effect |
| SePay real bank movement | NOT EXECUTED IN THIS WAVE | Requires an explicitly coordinated real incoming/outgoing transfer |

## 6. Defects and QA-harness fixes completed

1. OAuth users could exist without the normal `CustomerProfile` invariant.
   Existing mappings, verified Google accounts and new OAuth customers now all
   ensure the profile link; focused and full regressions pass.
2. Stateful ADMIN/STAFF E2E tests could invalidate one another when run in
   parallel. Playwright now uses one worker for the shared single-device
   accounts.
3. Runtime SePay E2E tests hardcoded a QA backend port. They now accept
   `E2E_QA_API`, allowing the same tests against the native backend at `8080`.
4. The CUSTOMER dashboard redirect assertion could fail only during the first
   local cold compile of `/account`. Product access was already denied; the
   strict URL assertion now allows 15 seconds for dev compilation.
5. Native Windows builds no longer require standalone trace output. Linux
   Docker/production can still opt into the standalone artifact.

No reservation, payment, refund, RoomHold, ledger, check-in or checkout
business ordering was changed by these QA-harness fixes.

## 7. Remaining production gaps

1. **Email deliverability:** the configured From address uses `gmail.com`
   through SendGrid and the SendGrid account has no Domain Authentication.
   Single Sender is suitable for testing, but Inbox delivery is not reliable.
   The attempted `luxury-hotel.publicvm.com` option was explicitly abandoned.
2. **SendGrid continuity:** the current account is a timed trial ending
   2026-09-13 according to the account UI. Production email needs a sustainable
   plan/provider decision before that date.
3. **Real SePay transfer:** automated and security contracts pass, but an
   operator still needs to execute one coordinated real incoming and one real
   outgoing transfer on production and reconcile both against the ledger.
4. **Release operations:** the production-clone restore/validation drill is
   complete. Sustained load/concurrency at production-like volume, final
   operator sign-off and monitoring alert receipt remain release gates.

## 8. Conclusion

The local application, PostgreSQL schema, backend tests, frontend tests/build
and critical browser workflows are green at the audited revision plus the
current dirty refactor changes. This is strong local release evidence, but it
is not yet a production-complete DoD because SendGrid deliverability/continuity,
real SePay transfer evidence and operator/load/monitoring gates remain PARTIAL.
