## Task handoff

- Khi bắt đầu phiên mới, hãy đọc file `HANDOFF.md` nếu tồn tại.
- Trước khi kết thúc một tác vụ dài, hãy cập nhật `HANDOFF.md`.
- Không xóa nội dung bàn giao khi công việc chưa hoàn thành.

## Production release candidate — 2026-07-26

- Release-readiness refresh 2026-08-02 on branch
  `fix/release-readiness-20260802`: backend/Flyway PostgreSQL gate passes 522
  unit tests plus 27 integration tests; frontend passes ESLint, explicit
  TypeScript checking, 71 unit tests and a 47-route production build.
- Vercel Native Deployment Checks `Lint` and `Typecheck` are configured as
  blocking production promotion checks. GitHub ruleset is prepared with
  required `Branch policy`, `Backend and PostgreSQL 16`, `Frontend` checks and
  up-to-date branches; GitHub still requires the repository owner to complete
  the open re-authentication dialog before the ruleset save is durable.
- Booking keeps the action available and shows an explicit inline error when
  terms have not been accepted. Reservation search
  preserves an invalid check-out value, explains the validation failure and
  clears stale availability instead of silently restoring defaults.
- Chatbot public FAQ no longer performs unconditional catalog/N+1 review
  requests, has bounded internal/Gemini timeouts and returns deterministic
  room-package pricing for common room-tier questions. Production previously
  reproduced a ~57-second 502; deploy and repeat the same browser check before
  marking the live chatbot fixed.
- Production auth remains first-party through Vercel `/backend_proxy`, so
  `Secure` + `SameSite=Lax` is the canonical cookie topology. `None` is only
  valid if the browser is deliberately changed to call Render cross-site and
  the entire login/refresh/logout/OAuth matrix is rerun.

- Production verification was refreshed on 2026-07-30 at deployed ref
  `87e9b3c`: ADMIN/STAFF role boundaries, cash walk-in/check-out, invoice,
  audit and financial journal were checked directly in the deployed UI.
- Reservation `RES-18557480` is the cash UAT evidence: 70,000 VND is identical
  across payment, checkout reconciliation, invoice, `CASH_IN`,
  `REVENUE_RECOGNIZED` and audit trail.
- The live finance dashboard and checkout-exception page currently show zero
  unresolved cash-flow/checkout exceptions. The earlier 2,000 VND SePay item
  is no longer in the active review queue.
- A bounded 24-request read-only smoke returned HTTP 200 throughout (p95 home
  1,625 ms, rooms 560 ms, backend health 278 ms). Keep the production-like
  load gate PARTIAL until it is run against an approved staging/Neon clone.
- Neon restore rehearsal is complete on child branch
  `pre-go-live-20260730`: reset from `production` succeeded and all ten
  `post-cutover-validate.sql` statements passed, including FK, identity,
  constraint and 28-migration checks. The expiring clone auto-deletes on
  2026-07-31 at 00:42 GMT+7.
- Remaining release evidence: coordinated real-bank SePay incoming/outgoing,
  sustained staging load, monitoring-email receipt and final operator
  sign-off.

- Active checkout remains `C:\Users\admin\Downloads\hotelmanagement-new`; the
  OneDrive checkout is not the release source.
- Backend SOLID refactor preserves the existing REST/database contracts and
  reservation, RoomHold, SePay, refund, ledger, check-in and checkout ordering.
- VNPay runtime code/config has been removed. PostgreSQL migration V11 fails
  closed if unsupported provider history exists, removes VNPay-only columns and
  constrains payment/refund/provider-event data to the supported SePay/CASH
  contracts. The production Neon preflight was confirmed to contain no VNPay
  history.
- Final local release evidence: 321 backend tests, eight PostgreSQL migration
  tests (fresh/upgrade/idempotency/legacy rejection), nine frontend unit tests,
  16 browser E2E scenarios, frontend lint/build (42 routes) and the backend
  Docker image build all pass.
- Render production remains on the Free Singapore service and Vercel on the
  Hobby project. Required Neon/JWT/OAuth/Cloudinary/SendGrid/SePay/bank and
  frontend proxy variables are present and masked in their dashboards.
- Remaining external evidence is intentionally tracked as PARTIAL in
  `docs/qa/full-system-test-report.md`: SendGrid inbox deliverability and plan
  continuity, a coordinated real SePay incoming/outgoing transfer, production
  load/operator UAT and monitoring alert receipt. Neon restore validation is
  complete.

## PostgreSQL database cutover — 2026-07-19

- Runtime database support is PostgreSQL-only: PostgreSQL JDBC/Flyway modules,
  `application.yml`, `.env.example`, local `.env`, and Docker Compose all use
  `jdbc:postgresql`.
- Active Flyway location is `classpath:db/migration-postgres`. V1 is the
  consolidated PostgreSQL baseline (34 application tables); V2 adds
  workload-aligned indexes/data checks and removes seven redundant indexes
  covered by new composite prefixes; V3 normalizes the boolean default without
  changing the V1/V2 checksums.
- Local Compose publishes PostgreSQL on host port `5433` because this Windows
  machine already has a listener on `5432`; container-to-container traffic
  remains `postgres:5432`. H2 is test-scope only and is absent from the runtime
  jar; the unused `pgcrypto` extension was removed from the baseline.
- No alternate database migration directory is included or scanned by the
  application; Flyway has one PostgreSQL source of truth.
- PostgreSQL Testcontainers gates cover clean V1→V3, Hibernate
  `ddl-auto=validate`, PostgreSQL type/index/constraint assertions, imported-ID
  identity reseeding, native/enum queries, and 10-way idempotency concurrency.
  Current evidence: 208 normal tests and four PostgreSQL migration tests pass.
- PostgreSQL backup, validation and rollback boundaries are documented in
  `docs/database/postgresql.md`. Do not reopen writes before backup, row/
  financial reconciliation and operator UAT.

## Payment platform compatibility release — 2026-07-18

- Repository hiện ở `C:\Users\admin\Downloads\hotelmanagement-new`.
- Implemented the PostgreSQL V1/V2/V3 schema and PostgreSQL Testcontainers gates.
- Implemented allocation/refund ledgers, durable SePay dedup/retry/review,
  idempotency, reconciliation cursor, inventory metadata/locking, purpose-aware
  expiry, audit/invoice v2, atomic walk-in and refund cancellation/reactivation.
- Completed canonical financial UTC dual fields, normalized invoice snapshot,
  merchant-account webhook rejection, mandatory idempotency for financial and
  operational mutations, PDF refund proof, no-show guard and concurrency tests.
- Idempotency hardening now commits claim + domain mutation + completion in one
  transaction, canonicalizes JSON payload hashes and retries/replays unique
  conflicts and transient transaction deadlocks. `POST /api/reservations` now also
  requires the key; guest create derives a SHA-256 capability from it. Booking
  page and chatbot reuse the same key for network retries.
- Reservation cardinality remains one reservation to many RoomTypes and many
  physical rooms. The lock invariant is only that one physical Room cannot be
  assigned to two active overlapping stays.
- V29 adds SePay outgoing confirmation for QR refunds: API-key authentication,
  exact `refund_code + expected_amount` matching, durable replay protection,
  time-gated manual fallback and a single refund-completion finalizer shared
  with cash handover.
- Verification at handoff: backend 208/208 tests passed; frontend production
  build passed; PostgreSQL V1→V3, Hibernate schema validation, local sequence
  finalization and post-cutover validation passed.
  Local Spring web startup against PostgreSQL also returned HTTP 200 from
  `GET /actuator/health`. `IdempotencyRequest.requestHash` and the invoice
  `currency` / `snapshotHash` mappings are explicitly aligned with Flyway
  `CHAR` columns.
- Dedicated H2 and PostgreSQL idempotency gates send 10 concurrent requests
  with one key and assert all callers receive the same resource while the
  action runs exactly once.
- Do not remove compatibility columns, legacy endpoints/status aliases or the
  existing `(provider, provider_reference)` unique key without a separately
  approved contract migration.
- Remaining production rollout gates: run `post-cutover-finalize.sql` then
  `post-cutover-validate.sql` against a PostgreSQL backup/staging clone,
  configure secrets and merchant account values, then complete
  concurrency/load and operator UAT.
- Local `.env` now includes the PostgreSQL pool/Flyway retry settings. The
  existing `SEPAY_WEBHOOK_SECRET` remains a compatibility alias for
  `SEPAY_WEBHOOK_API_KEY`; before deployment, move to the canonical variable and
  configure the exact same value in SePay, never writing it to docs/logs.
- Local and ngrok provider-test probes both returned HTTP 200 with
  `{"success":true}` on `/api/payments/sepay/webhook`. The ngrok inspector also
  recorded the public POST as 200. Online UI exposes SePay VietQR only.
- Dev profile disables DevTools persistent HTTP sessions and uses target-local
  Tomcat directories to avoid Windows `ApplicationTemp` ownership failures.
- The opt-in `postgres-migration-test` profile filters `target/classes` for the test
  profile. If a dev process with DevTools is already running, finish the gate by
  rebuilding `mvn -Pdev -DskipTests package`; otherwise the live process may
  temporarily reload test webhook credentials and return 401.
- Báo cáo hợp nhất: `docs/payment-platform/consolidated-implementation-report.md`.
