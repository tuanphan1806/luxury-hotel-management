# Hotel reservation payment platform implementation plan

## Database decision

PostgreSQL 16 is the only supported database for this project. The backend
uses the PostgreSQL JDBC driver and Flyway PostgreSQL adapter; the runtime
does not package a second database driver. H2 is test-scope only.

## Migration chain

1. `V1__baseline_schema.sql` creates the complete 34-table application schema.
2. `V2__postgresql_optimization.sql` adds workload-aligned composite/partial
   indexes, financial data checks and removes redundant indexes.
3. Every future schema change must be a new migration under
   `db/migration-postgres`; no ad-hoc DDL or alternate migration location is
   supported.

The baseline preserves API compatibility values such as `DRAFT`, uses bounded
text for status-like values, maps financial instants to UTC-aware PostgreSQL
timestamps, and keeps legacy nullable fields where the business contract still
needs them. No destructive data rewrite is hidden inside application startup.

## Locked business decisions

- `DRAFT` remains the database/API compatibility value for
  `PENDING_CONFIRMATION` during the first stable release.
- Stay dates/times remain `LocalDateTime` in `Asia/Ho_Chi_Minh`; new financial
  and provider timestamps use `Instant`/UTC.
- `accepted_amount` and `refund_required_amount` remain nullable when the
  business state is genuinely unknown.
- The legacy `(provider, provider_reference)` uniqueness contract remains.
- `sessionStorage` guest-token behavior is `PARTIAL`: it survives refresh and
  same-tab redirects, but not tab closure or a new browser context.

## Implemented payment and reservation behavior

- Payment allocation uses `expected_amount`, `received_amount`,
  `accepted_amount` and `refund_required_amount`.
- SePay webhook/reconciliation share durable provider-event state, canonical
  deduplication, UTC provider time, retry metadata and staff review actions.
- QR refunds require an exact `refund_code + expected_amount` match; replay is
  idempotent and unmatched transfers enter review.
- Refunds are durable ledger rows with encrypted recipients, explicit cash or
  bank completion, cancellation/reactivation and uncovered-obligation guards.
- Financial and operational mutations require durable `Idempotency-Key`
  handling, including reservation creation, payment, refund, walk-in,
  cancellation, check-in, checkout and no-show.
- Walk-in `CASH`, `SEPAY` and `UNPAID` use the atomic `/walk-in/v2` command.
- Inventory counts only sellable, non-decommissioned rooms; deterministic
  RoomType/Room locking protects concurrent assignment.
- Expiry is purpose-aware and can recover an on-time deposit event received
  after the local payment timeout when inventory is still available.
- A reservation can contain multiple RoomTypes and quantities; each reserved
  slot is assigned one matching physical Room at check-in.

## Required gates

- Run backend unit/integration tests and the PostgreSQL migration profile.
- Require Hibernate `ddl-auto=validate` against the migrated schema.
- Run `db/postgres/post-cutover-validate.sql` against a named PostgreSQL
  backup/staging clone before a production claim.
- Reconcile payment, refund and invoice row counts and totals before reopening
  traffic.
- Complete provider credential/merchant verification, load/concurrency tests,
  operator UAT, observability and backup/restore rehearsal.

## Verification snapshot

- Backend: `mvn test` — **208 tests passed**, 0 failures/errors/skips.
- PostgreSQL migration and concurrency Testcontainers gates passed.
- Frontend: `pnpm.cmd run build` — production build passed and 39 routes were
  generated.
- Local PostgreSQL startup and `/actuator/health` returned `UP` during smoke
  verification.
