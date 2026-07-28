# Operational accounting roadmap

## Delivery status (2026-07-29)

- **Phase 2A complete locally:** cashier shifts, opening float, immutable cash
  movements, exact/over/short close, STAFF ownership, ADMIN read access,
  idempotent manual mutations, audit trail and dashboard UI.
- CASH reservation payments, CASH walk-ins and CASH-at-counter refunds now post
  to the operator's open shift in the same database transaction. SePay remains
  independent from cashier shifts.
- PostgreSQL migration V25 is expand-only and intentionally does not infer or
  backfill historical cash movements.
- **Phase 2B complete locally:** balanced automatic journal, durable SePay
  unmatched-funds observation, late provider posting, immutable business-day
  close, per-day locking, database enforcement, ADMIN API/UI and audit trail.
- V26–V28 clean migration, early-V26 compatibility upgrade and schema
  validation pass on PostgreSQL 16. Immutable close additionally requires an
  explicit `APP_ACCOUNTING_GO_LIVE_DATE`. Production
  remains deliberately undeployed until snapshot/staging reconciliation and
  operator UAT are complete.

## Decision

The current release is a complete read-only **financial reporting and
operational reconciliation** module. It is not presented as statutory or full
double-entry accounting.

The project accepts both SePay and counter cash, including cash payments and
cash refunds. Cashier accountability and business-day close are now delivered
as separate reviewed phases. ADMIN cannot edit journal totals or create
free-form financial postings.

## Phase 2 boundary

Build only after auditing every existing CASH creation/refund entry point,
actor source and Idempotency-Key contract:

1. **Cashier shift**: one open shift per staff/terminal; opening float,
   expected cash, counted cash, variance, opener/closer and timestamps.
2. **Immutable cash movement**: payment/refund movements link to their source;
   approved manual cash-in/out requires category, reason, actor and approver.
3. **Business-day close**: SePay, cash, accepted amount, completed refund,
   recognized invoice, deposits, refund payable, open shifts and unresolved
   provider events in one close preview.
4. **Day lock**: a closed day rejects backdated financial mutation. Corrections
   use a linked reversal/current-day adjustment; completed source rows are not
   overwritten or deleted.
5. **Small automatic journal**: only canonical payment, refund and invoice
   events; balanced lines, unique `(source_type, source_id, posting_kind)` and
   atomic posting/outbox semantics. No free-form ADMIN journal in this phase.

SePay transfers do not require a cashier shift. Every CASH payment or CASH
refund does, unless an explicitly audited supervisor emergency path is later
approved.

## Minimum internal accounts

- `CASH_ON_HAND`
- `BANK_SEPAY`
- `CUSTOMER_DEPOSIT`
- `REFUND_PAYABLE`
- `ROOM_REVENUE`
- `SERVICE_REVENUE`
- `DISCOUNT`
- `TAX_PAYABLE`

This compact list exists to classify hotel money, not to claim legal accounting
compliance.

## Required invariants

- Every completed financial source posts at most once under retries.
- Every journal entry balances exactly: total debit equals total credit.
- Posted entries and completed cash movements have no update/delete API.
- Reversal references its original record and preserves both histories.
- Closing a shift revalidates expected cash in one database transaction.
- Closing a business day fails while a shift is open, a provider event is
  unresolved, a completed financial source is unposted or a journal is
  unbalanced.
- A locked day cannot be changed through payment, refund, invoice, cash or
  manual-maintenance endpoints.
- All high-risk actions record actor, role, reason and audit detail.

## Migration and implementation order

1. Audit existing CASH/payment/refund paths and publish a gap table.
2. Add append-only shift/movement/close/journal tables and unique constraints.
3. Backfill nothing automatically; reconcile a staging snapshot first.
4. Integrate one source at a time: CASH payment, CASH refund, SePay payment,
   SePay refund, immutable invoice.
5. Run full regression after each source; then enable day close and lock.
6. Add ADMIN close/review UI and STAFF shift UI only after API invariants pass.

## Tests required before rollout

- Retry/concurrency: double open/close, duplicate source posting, reversal and
  two staff closing the same shift.
- Crash recovery between source completion and journal posting.
- Cash payment/refund cannot use a closed or another user's shift.
- Exact cash, over/short variance and supervisor rejection/approval.
- Close-day blockers and locked-day mutation rejection.
- SePay incoming/outgoing reconciliation remains idempotent and does not depend
  on a cash shift.
- Existing reservation, RoomHold, payment, refund, invoice and checkout suites
  remain unchanged and pass.

## Explicitly deferred

Supplier management, accounts payable, payroll, depreciation, a full chart of
accounts, trial balance, profit-and-loss statements, month close, tax and
electronic-invoice compliance remain outside this hotel MVP until separately
specified and reviewed by an accounting professional.
