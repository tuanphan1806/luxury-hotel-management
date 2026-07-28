# Phase 2B design — automatic journal and business-day close

## Status and boundary

This document records the implemented Phase 2B contract. Phase 2A cashier
shifts and Phase 2B journal/day-close are complete locally. V26–V28, source
integration, authorization, frontend and automated regression gates pass;
production still requires a database snapshot, staged V26–V28 rollout and operator
UAT before the feature is treated as live.

The module is a compact operational journal for hotel reconciliation. It is
not statutory accounting and does not add a free-form ADMIN journal.

## Repository audit and gap table

| Area | Current state | Gap | Phase 2B decision |
| --- | --- | --- | --- |
| CASH payment and refund | Completed sources append an immutable movement to the operator's open shift in the same transaction | No balanced financial entry | Keep the shift ledger and add one balanced journal entry in the same transaction |
| SePay incoming and outgoing | Provider events are durable and idempotent; payment/refund completion is canonical | No balanced financial entry; late provider events can arrive after a business day was closed | Post from the canonical source exactly once; preserve provider time and post a linked late adjustment on the current open day when the original day is locked |
| Invoice snapshot | One immutable invoice per reservation with normalized pricing breakdown and hash | Revenue is visible in reports but not journalized | Post revenue only after the invoice row is persisted; one entry per invoice |
| Business reporting | Read-only revenue, cash-flow, booking, occupancy, reservation and virtual ledger views exist | No immutable close snapshot or locked day | Add close preview/close endpoints and immutable daily close snapshot |
| Day lock | Missing | Backdated mutations can change a previously reviewed period | Enforce at the canonical financial posting boundary; operator backdating is rejected, late provider events use a current-day adjustment |
| Crash recovery | Source transactions are atomic, but no journal exists | A future asynchronous projector could leave completed sources unposted | Use synchronous journal posting in the same database transaction. No asynchronous outbox is needed for Phase 2B |
| Historical data | V1–V25 contain valid legacy rows | Guessing journal history would create false accounting evidence | No automatic backfill; historical reconciliation/import remains an explicit staging operation |

## Canonical source transitions

Only the following transitions may create a journal entry:

1. `PaymentTransaction` first becomes `SUCCESS`:
   - CASH payment in `PaymentService.createCashPayment`;
   - CASH walk-in in `ReservationServiceImpl.createWalkInCheckedIn`;
   - SePay payment accepted, underpaid, late or overpaid in `SePayService`;
   - SePay additional transfer row created as `SUCCESS`.
2. `PaymentRefund` first becomes `SUCCEEDED` in
   `PaymentRefundService.completeRefund`.
3. `ReservationInvoice` is first persisted by
   `ReservationInvoiceSnapshotService.createSnapshot`.

Changing a payment back from `REFUND_PENDING`/`REFUNDED` to `SUCCESS` does not
post another receipt. The unique key `(source_type, source_id, posting_kind)`
is the database-level idempotency boundary.

## Posting model

### Accounts

- `CASH_ON_HAND`
- `BANK_SEPAY`
- `CUSTOMER_DEPOSIT`
- `REFUND_PAYABLE`
- `ROOM_REVENUE`
- `SERVICE_REVENUE`
- `DISCOUNT`
- `TAX_PAYABLE`
- `UNRECONCILED_FUNDS`

`UNRECONCILED_FUNDS` is required because an authenticated SePay event is real
bank movement even when no reservation/refund can be matched yet. Omitting it
would make the journal lose money that is already visible at the bank.

### Payment received

- Debit `CASH_ON_HAND` or `BANK_SEPAY` by `receivedAmount`.
- Credit `CUSTOMER_DEPOSIT` by `acceptedAmount`.
- Credit `REFUND_PAYABLE` by `refundRequiredAmount`.
- The three canonical allocation fields must be non-negative and
  `receivedAmount = acceptedAmount + refundRequiredAmount`. A mismatch is a
  data-quality failure; the journal must not invent an allocation.

### Refund completed

- Credit `CASH_ON_HAND` for `CASH_AT_COUNTER`, otherwise `BANK_SEPAY`.
- Debit `REFUND_PAYABLE` for unaccepted/additional/overpayment/unmatched money.
- Debit `CUSTOMER_DEPOSIT` for an accepted reservation allocation.
- Use `actualRefundAmount` when present; otherwise use `amount`.

### Unmatched provider movement and later classification

- Unmatched incoming: debit `BANK_SEPAY`, credit `UNRECONCILED_FUNDS`.
- Unmatched outgoing: debit `UNRECONCILED_FUNDS`, credit `BANK_SEPAY`.
- A later payment match reclassifies `UNRECONCILED_FUNDS` to
  `CUSTOMER_DEPOSIT`/`REFUND_PAYABLE`; it never debits the bank twice.
- A later refund match reclassifies the correct liability to
  `UNRECONCILED_FUNDS`; it never credits the bank twice.
- A non-zero daily `UNRECONCILED_FUNDS` balance blocks business-day close even
  if an operator marked the provider event `IGNORED`.

### Invoice issued

- Debit `CUSTOMER_DEPOSIT` by the immutable invoice total.
- Credit `ROOM_REVENUE` by actual room charge plus extra-guest and stay
  adjustments represented in the immutable snapshot.
- Credit `SERVICE_REVENUE` by confirmed/fulfilled add-on services and approved
  additional fees.
- Debit `DISCOUNT` for a positive discount.
- Credit `TAX_PAYABLE` for tax.
- The entry must balance exactly. The immutable invoice remains the source of
  truth; the journal never recalculates mutable reservation fields later.

## Business date and locked-day policy

- All business dates use `Asia/Ho_Chi_Minh`; source timestamps remain `Instant`
  UTC.
- Normal CASH, refund, invoice and maintenance mutations cannot post to a
  closed business date.
- A SePay event can arrive after downtime for a provider timestamp that belongs
  to a closed day. Real money must not be lost or forced into endless retries:
  the operational source is still reconciled, while the journal entry is
  appended to the current open business date with `original_business_date`,
  provider timestamp and `late_posting=true` preserved in detail.
- A closed journal day is never reopened or overwritten.
- Journal posting and day close acquire the same per-date PostgreSQL row lock.
  This serializes the final preview/save with concurrent payment, refund,
  invoice and webhook postings. Database triggers also reject direct inserts
  into an already closed day or appending lines after that day is closed.

## Close preview and close blockers

ADMIN-only endpoints will expose a read-only preview and an idempotent close.
Closing fails when any of these are true for the selected business date:

1. a cashier shift remains `OPEN` or `CLOSING`;
2. a SePay event is `RECEIVED`, `PROCESSING`, `FAILED_RETRYABLE` or
   `REVIEW_REQUIRED`;
3. a completed payment, refund or invoice has no journal entry;
4. any journal entry is unbalanced;
5. the date is in the future or is already closed with different request data.

Pending refund obligations are shown in the close snapshot but do not block
close; they remain liabilities carried into the next day. This value means the
open refund obligations visible when the close transaction executes. It is
stored in the immutable close snapshot and is not presented as a reconstructed
historical balance from refund events alone.

Closing is also blocked until `APP_ACCOUNTING_GO_LIVE_DATE` is configured and
for every date before that boundary. V26 intentionally does not invent journal
entries for legacy payment/refund/invoice rows, so allowing an older date to be
closed would create a misleading partial snapshot. The configuration only gates
day close; it does not stop payment, refund, invoice or SePay processing.

## Migration order

1. `V26`: journal entries, journal lines, day-close mutex and business-day
   closes; unique source key, indexes, append-only/closed-day triggers.
2. `V27`: idempotently aligns early development V26 schemas, normalizes the
   journal currency/close hash types and guarantees both closed-day insert
   triggers exist.
3. `V28`: adds partial indexes for journal links to payment, refund, invoice
   and provider-event sources used by close-time completeness checks.
4. Entities/repositories and the source-independent posting service.
5. Source integration: CASH payment, CASH refund, SePay payment/refund,
   unmatched provider movement and immutable invoice.
6. ADMIN close preview/close/history/journal API and dashboard UI.
7. Completed locally: clean PostgreSQL Flyway V1–V28, Hibernate schema
   validation, idempotency/concurrency tests and full backend/frontend gates.
8. Remaining rollout-only gates: snapshot Neon, apply V26–V28 on staging/clone,
   reconcile a real hotel day, run operator UAT, then deploy production.

## Explicit non-goals

No suppliers/AP, payroll, depreciation, free-form corrections, trial balance,
P&L, month close, tax filing or electronic-invoice compliance is introduced
in this phase.
