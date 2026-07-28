# Phase 2B implementation report — journal and business-day close

## Outcome

Phase 2B is complete and verified locally. Canonical payment, refund, provider
cash and immutable invoice transitions now append balanced, immutable journal
entries in the same database transaction. ADMIN can review and close a hotel
business date from the dashboard. This phase does not change reservation,
RoomHold, check-in, checkout, refund or SePay state machines.

Production is not yet marked complete: V26–V28 must first be applied to a backed-up
staging/Neon clone and reconciled against one real operating day.

## Implemented backend

- Compact chart of accounts for cash, SePay bank, customer deposit, refund
  payable, room/service revenue, discount, tax and unreconciled funds.
- Unique source boundary `(source_type, source_id, posting_kind)` plus a
  post-lock idempotency recheck for concurrent retries.
- Atomic hooks for CASH payment/walk-in, SePay incoming/outgoing and matching,
  completed refunds and immutable invoices.
- Real unmatched SePay movement remains visible in `UNRECONCILED_FUNDS` and
  blocks close until classified.
- Closed provider dates use a linked current-day late posting; original hotel
  date and provider time are retained and a SYSTEM audit event is written.
- ADMIN-only preview, close, close-history and journal endpoints.
- Close blockers for active shifts, unresolved provider events, unposted
  completed sources, unbalanced entries and unreconciled funds.
- Day close remains disabled when `APP_ACCOUNTING_GO_LIVE_DATE` is absent or
  the selected date precedes it. This prevents a partial, non-backfilled legacy
  period from being presented as a complete close.
- Close preview and journal pagination now use aggregate/batch queries instead
  of loading every source row or issuing one line query per journal entry.

## Database integrity

`V26__financial_journal_and_business_day_close.sql` adds:

- `financial_journal_entries` and `financial_journal_lines`;
- `business_day_closes` and `business_day_close_locks`;
- source idempotency, lookup and business-date indexes;
- append-only update/delete triggers;
- triggers preventing direct entry/line insertion into a closed day.

`V27__align_financial_journal_close_guards.sql` is an idempotent compatibility
migration. It makes clean and early-development V26 schemas converge on the
same column types, function and closed-day triggers. The local development
database was repaired only after 97 schema objects matched a clean database
exactly (same SHA-256); no production Flyway history was changed.

`V28__index_financial_journal_sources.sql` adds partial source-link indexes used
by close-time unposted-source checks. PostgreSQL does not index foreign keys
automatically; this migration prevents the optimized aggregate checks from
falling back to repeated journal scans as the ledger grows.

Journal posting and close acquire the same per-date row mutex. Close therefore
revalidates after concurrent financial source transactions finish instead of
trusting an earlier UI preview.

## Frontend and access

- `/dashboard/business-days` is available only to ADMIN.
- The page shows close readiness, blocker explanations, daily totals,
  unresolved obligations, expandable debit/credit lines and immutable close
  history.
- Close uses an `Idempotency-Key`, a centered accessible modal, disabled/loading
  state and explicit confirmation. STAFF/CUSTOMER remain blocked by backend
  authorization even if they call the endpoint directly.

## Automated evidence (2026-07-29)

- Backend unit/integration regression: 496/496 passed.
- PostgreSQL 16 migration profile: 26/26 passed, including clean V1–V28,
  Hibernate schema validation, trigger immutability and idempotency.
- Frontend Vitest: 45/45 passed.
- Frontend TypeScript and ESLint: passed.
- Next.js production build: passed and generated `/dashboard/business-days`.
- `git diff --check`: passed (Windows line-ending notices only).

## Required production rollout

1. Snapshot Neon and verify the backup can be restored.
2. Inspect the staging clone's `flyway_schema_history` before deployment. Apply
   V26–V28 through normal Flyway startup. If V26 was previously applied with a
   different checksum, stop and repeat the documented schema comparison; never
   run an unconditional repair.
3. Reconcile a representative day against cashier shifts, SePay provider
   events, payments, refunds and invoices.
4. UAT an exact cash shift, over/short shift, unmatched incoming/outgoing,
   late SePay event and blocked/allowed day close.
5. Set `APP_ACCOUNTING_GO_LIVE_DATE` to the first date from which journal
   coverage is complete. Do not choose an earlier legacy date.
6. Deploy backend before frontend, verify ADMIN/STAFF authorization, then
   enable the dashboard route.

## Explicit non-goals

This is not a statutory general ledger. Supplier/AP, payroll, expenses,
depreciation, unrestricted corrections, trial balance, profit-and-loss,
month-end close, tax filing and compliant electronic invoicing are not part of
the hotel MVP.
