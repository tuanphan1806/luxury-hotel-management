# Cashier shift and physical-cash ledger implementation report

## Delivered boundary

This phase adds operational accountability for physical cash. It does not
change SePay matching, RoomHold, reservation state transitions, invoice
calculation or checkout reconciliation.

- One active cashier shift per ADMIN/STAFF user.
- Opening float, counter CASH payment, CASH walk-in, CASH-at-counter refund and
  reasoned manual cash-in/cash-out are append-only movements.
- Cash payment/refund completion and its movement share one transaction. If no
  open shift exists, the entire financial operation rolls back.
- Shift close locks the shift, recalculates expected cash under a row lock and
  requires a reason for every non-zero variance.
- STAFF can mutate and read only their own shift; ADMIN can read every shift
  but cannot mutate another operator's shift.
- Sensitive HTTP mutations require `Idempotency-Key` and reuse the existing
  persisted idempotency boundary.
- Audit actions identify opener/closer/movement actor; cash variance is high
  risk.

## API

Base path: `/api/accounting/cashier-shifts`, ADMIN/STAFF only.

- `POST /` — open shift.
- `GET /current` — current operator's active shift.
- `GET /` — paged history; ADMIN sees all, STAFF only their own.
- `GET /{shiftId}` — immutable shift detail and movements.
- `GET /{shiftId}/preview-close` — read-only expected-cash recomputation.
- `POST /{shiftId}/cash-in` and `/cash-out` — reasoned manual movement.
- `POST /{shiftId}/close` — exact or reasoned over/short close.

## Database

Migration `V25__cashier_shifts_and_cash_movements.sql` adds:

- `cashier_shifts` with actor snapshots, business date, optimistic version,
  expected/count/variance and a partial unique active-user index;
- `cash_movements` with source links and a unique source/type key;
- PostgreSQL triggers preventing movement update/delete/truncate and preventing
  mutation/deletion of a closed shift.

No historical CASH row is guessed into a shift. Production rollout must start
with a documented opening balance after V25 is applied.

## Dashboard

`/dashboard/cashier-shifts` is in the Vận hành navigation for both roles. It
supports open, reasoned cash-in/out, close preview, variance explanation,
immutable movement detail and paged shift history. Modals use the shared
focus-trapped/click-outside/Escape component.

## Local verification evidence (2026-07-28)

- Backend regression: 456 tests, 0 failures, 0 errors, 0 skipped.
- PostgreSQL 16/Flyway profile: 23 migration/integration tests passed; clean
  migration V1-V25.
- V25 tests cover active-shift uniqueness, append-only movements and immutable
  closed shifts.
- Frontend Vitest: 43 tests passed.
- ESLint: passed.
- Next.js production build: compiled and generated 45 routes, including
  `/dashboard/cashier-shifts`.

## Rollout gates still required

1. Backup Neon and deploy backend so Flyway applies V25 before the frontend is
   exposed.
2. Open a real STAFF shift with the actual counted opening float.
3. UAT one CASH payment, one CASH walk-in, one CASH refund, retry each request,
   then close exact and close with a controlled variance on a separate shift.
4. Confirm SePay incoming/outgoing flows remain usable without a cashier shift.
5. Reconcile the first production shift against physical cash before treating
   this control as operationally accepted.

Business-day close/day lock and automatic journals remain Phase 2B; see
`operational-accounting-roadmap.md`.
