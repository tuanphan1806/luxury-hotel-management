## Task handoff

- Khi bắt đầu phiên mới, hãy đọc file `HANDOFF.md` nếu tồn tại.
- Trước khi kết thúc một tác vụ dài, hãy cập nhật `HANDOFF.md`.
- Không xóa nội dung bàn giao khi công việc chưa hoàn thành.

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
  recorded the public POST as 200. Online UI continues to expose QR, not VNPay.
- Dev profile disables DevTools persistent HTTP sessions and uses target-local
  Tomcat directories to avoid Windows `ApplicationTemp` ownership failures.
- The opt-in `postgres-migration-test` profile filters `target/classes` for the test
  profile. If a dev process with DevTools is already running, finish the gate by
  rebuilding `mvn -Pdev -DskipTests package`; otherwise the live process may
  temporarily reload test webhook credentials and return 401.
- Báo cáo hợp nhất: `docs/payment-platform/consolidated-implementation-report.md`.
