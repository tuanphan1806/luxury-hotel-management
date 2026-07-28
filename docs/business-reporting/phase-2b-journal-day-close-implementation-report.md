# Phase 2B implementation report — journal and business-day close

## Outcome

Phase 2B is complete and verified locally. Canonical payment, refund, provider
cash and immutable invoice transitions now append balanced, immutable journal
entries in the same database transaction. ADMIN can review and close a hotel
business date from the dashboard. This phase does not change reservation,
RoomHold, check-in, checkout, refund or SePay state machines.

Production is not yet marked complete: V26 must first be applied to a backed-up
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

## Database integrity

`V26__financial_journal_and_business_day_close.sql` adds:

- `financial_journal_entries` and `financial_journal_lines`;
- `business_day_closes` and `business_day_close_locks`;
- source idempotency, lookup and business-date indexes;
- append-only update/delete triggers;
- triggers preventing direct entry/line insertion into a closed day.

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

- Backend unit/integration regression: 470/470 passed.
- PostgreSQL 16 migration profile: 24/24 passed, including clean V1–V26,
  Hibernate schema validation, trigger immutability and idempotency.
- Frontend Vitest: 45/45 passed.
- Frontend TypeScript and ESLint: passed.
- Next.js production build: passed and generated `/dashboard/business-days`.
- `git diff --check`: passed (Windows line-ending notices only).

## Required production rollout

1. Snapshot Neon and verify the backup can be restored.
2. Apply V26 to a staging clone through normal Flyway startup.
3. Reconcile a representative day against cashier shifts, SePay provider
   events, payments, refunds and invoices.
4. UAT an exact cash shift, over/short shift, unmatched incoming/outgoing,
   late SePay event and blocked/allowed day close.
5. Deploy backend before frontend, verify ADMIN/STAFF authorization, then
   enable the dashboard route.

## Explicit non-goals

This is not a statutory general ledger. Supplier/AP, payroll, expenses,
depreciation, unrestricted corrections, trial balance, profit-and-loss,
month-end close, tax filing and compliant electronic invoicing are not part of
the hotel MVP.
