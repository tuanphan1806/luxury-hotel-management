# Business reporting and financial operations dashboard

## 1. Scope

This module is a read-only management view over the canonical Reservation,
PaymentTransaction, PaymentRefund and ReservationInvoice data. It does not
post money, change reservation status, bypass SePay, alter refunds or override
checkout reconciliation.

The first release deliberately separates three concepts that must not be
combined:

1. **Recognized revenue**: immutable invoice value issued at checkout.
2. **Cash flow**: money actually received, less completed refunds.
3. **Open obligations**: receivables, customer deposits and refund payable at
   the time the report is opened.

System/HTTP monitoring remains in `BusinessMetricService` and
`BusinessMonitoringService`. Business reporting does not reuse Micrometer
counters as an accounting source.

## 2. Access control

- All `/api/admin/statistics/**` endpoints require `ROLE_ADMIN` at the service
  boundary through `@PreAuthorize`.
- STAFF keeps the existing money-free `/api/operations/summary` dashboard and
  cannot access revenue, ledger or CSV endpoints.
- The frontend only renders `/dashboard/statistics` in the ADMIN sidebar. UI
  hiding is convenience only; backend authorization is authoritative.

## 3. Time and period rules

- The report receives inclusive hotel dates: `from=YYYY-MM-DD` and
  `to=YYYY-MM-DD`.
- The maximum range is five years. The default is the latest 30 hotel dates.
- All financial instants are stored as UTC (`paid_at_utc`,
  `completed_at_utc`, `issued_at_utc`) and converted to
  `Asia/Ho_Chi_Minh` before day/week/month truncation.
- Reservation/stay timestamps are currently PostgreSQL local timestamps and
  must not be converted a second time.
- Week buckets start on Monday. A partial first or last week is allowed and is
  still restricted to the requested date range.
- Comparison percentages use the immediately preceding period with exactly
  the same number of inclusive dates.

## 4. KPI definitions

| KPI | Canonical calculation | Event date |
|---|---|---|
| Recognized revenue | `SUM(reservation_invoices.total_amount)` | `issued_at_utc` |
| Gross cash received | `SUM(payment_transactions.received_amount)` for financially accepted payment statuses | `paid_at_utc` |
| Accepted cash | `SUM(payment_transactions.accepted_amount)` | `paid_at_utc` |
| Refund outflow | `SUM(payment_refunds.actual_refund_amount)` where status is `SUCCEEDED` | `completed_at_utc` |
| Net cash flow | gross cash received - refund outflow | cash event date |
| Bookings | reservations created in the period, broken down by their current status | local `created_at` |
| Sold room-hours | overlap between the report day and committed/actual stay, multiplied by room quantity | stay window |
| Available room-hours | rooms sellable during the day × 24 | inventory window |
| Occupancy | sold room-hours / available room-hours | stay window |
| Room-night equivalent | room-hours / 24 | stay window |
| ADR | allocated recognized room revenue / sold room-night equivalents | stay + invoice |
| RevPAR | allocated recognized room revenue / available room-night equivalents | stay + invoice |

For CHECKED_IN/CHECKED_OUT reservations, sold time begins at
`actual_check_in` when present. For CONFIRMED reservations it begins at the
planned check-in. CHECKED_OUT ends at `actual_check_out` when present. An
active overdue stay extends to the current hotel time; an active stay that is
not overdue remains committed through its planned checkout.

## 5. Data-quality contract

- Historical payments with null `received_amount` are **not silently counted**
  as canonical cash received. Their legacy amount/count is returned separately
  and the UI displays a warning.
- Positive SePay `in` events that are not linked to a payment remain visible as
  `UNMATCHED_CASH_IN`. Positive `out` events that are not the completion event
  of a `SUCCEEDED` refund remain visible as `UNCLASSIFIED_CASH_OUT`. A matched
  outgoing event is never counted twice beside its refund.
- Cancelling a refund row does not erase the underlying obligation. Refund
  payable includes active refund rows plus uncovered mandatory/cancellation
  obligations.
- The current schema does not contain a daily inventory/maintenance snapshot.
  Historical room availability is reconstructed from current rooms,
  `created_at`, `sellable`, `decommissioned_at` and current maintenance state.
  It is therefore labelled `ESTIMATED_INVENTORY_HISTORY`.
- Invoice snapshots are immutable and are the source for room/add-on revenue.
  Current mutable room prices are never used to rewrite old revenue.
- Booking status charts show the **current outcome** of reservations created in
  each period, not a historical status-event timeline.

## 6. API surface

| Endpoint | Purpose |
|---|---|
| `GET /api/admin/statistics/overview` | KPIs, previous-period change, cash and open obligations |
| `GET /api/admin/statistics/revenue` | recognized revenue, invoice count and fee breakdown |
| `GET /api/admin/statistics/cash-flow` | cash received/refunded series, optionally filtered by provider |
| `GET /api/admin/statistics/bookings` | booking series by current status |
| `GET /api/admin/statistics/occupancy` | hourly occupancy, ADR and RevPAR series |
| `GET /api/admin/statistics/room-types` | room-type performance table |
| `GET /api/admin/statistics/reservations` | paged per-reservation invoice/payment/refund detail with code/status search |
| `GET /api/admin/statistics/ledger` | paged searchable read-only union of cash-in, refund-out and recognized revenue |
| `GET /api/admin/statistics/export` | UTF-8 BOM CSV export for revenue/cash-flow/bookings/occupancy/room-types/reservations/ledger |

`granularity` accepts only `day`, `week` or `month`. Ledger page size is
limited to 100; CSV ledger export is capped at 10,000 entries and communicates
truncation through `X-Export-Truncated: true`. Provider/user-originated CSV
text is neutralized against spreadsheet formula injection. `cash-flow`
accepts the optional `provider` filter (for example `SEPAY` or `CASH`) and
returns payment/refund counts plus the amount received but not accepted toward
an obligation. Revenue exposes additional fee, late-checkout fee and immutable
invoice count; room-type performance includes both ADR and RevPAR.

## 7. Database migration

`V23__business_reporting_indexes.sql` and
`V24__report_unmatched_provider_cash.sql` are expand-only. They add
partial/range indexes on canonical reporting timestamps, reservation
stay/creation columns and unlinked provider cash events. They neither backfill
nor mutate financial data.

Deployment order:

1. Back up or snapshot the PostgreSQL database.
2. Run Flyway V23-V24 on staging/production through the normal backend startup.
3. Confirm Flyway is at V24 and all new indexes exist.
4. Deploy backend API.
5. Deploy frontend route.
6. Compare a short report period manually against reservation invoices,
   SePay/cash transactions and completed refunds.

## 8. Required manual UAT

1. Log in as STAFF: the Statistics menu must be absent; direct API access must
   return 403; the operational summary remains visible without money fields.
2. Log in as ADMIN and compare today/7 days/month-to-date.
3. Verify a payment near 00:00 Vietnam time appears on the correct hotel date.
4. Verify an overpayment: gross received includes all incoming money, accepted
   cash excludes excess, and the completed refund appears as outflow.
5. Verify a late actual check-in only contributes occupied hours from the
   actual check-in time.
6. Checkout a reservation and verify recognized revenue appears once, from the
   immutable invoice.
7. Filter cash flow by SePay/Cash and reconcile payment/refund counts with the
   read-only ledger.
8. Search reservation-revenue and ledger views, check pagination and all seven
   CSV exports in Excel/Sheets.

## 9. Operational accounting extension

Phase 2A and 2B now add cashier shifts, immutable cash movements, a compact
balanced journal and immutable business-day close on top of these reports.
See `cashier-shift-implementation-report.md` and
`phase-2b-journal-day-close-implementation-report.md`.

This is still operational accounting, not statutory accounting. Supplier/AP,
payroll, expenses, depreciation, free-form corrections, trial balance, P&L,
month close, tax filing and electronic-invoice compliance remain separately
scoped work requiring an accounting professional.

## 10. Local demo accounting data

Local development can opt into a coherent, idempotent dataset:

```properties
APP_SEED_MASTER_DATA_ENABLED=true
APP_SEED_DEMO_USERS_ENABLED=true
APP_SEED_DEMO_SCENARIOS_ENABLED=true
```

`DemoScenarioSeedService` resolves users, room types, rooms and services by
stable business keys rather than database IDs. It creates each scenario only
when its `DEMO-FIN-*` reservation code does not already exist. Repeated
application starts therefore skip the complete scenario instead of duplicating
payments, refunds, invoices or journal entries.

The current scenario matrix contains:

- payment pending, paid draft, confirmed deposit and confirmed multi-room
  bookings;
- an active checked-in stay;
- ten checked-out stays spread across the current day, recent weeks and three
  calendar months;
- hourly, overnight and daily pricing examples, multi-room bookings and
  booking-time add-on services;
- SePay and cash payments, one overpayment refund, cancellation refunds by
  bank and cash, and a no-show without fabricated cash;
- immutable checkout invoices, balanced financial journal entries and a
  completed demo cashier shift with matching cash movements.

Cash scenarios run inside a dedicated demo shift. The seeder refuses to attach
fixture movements to a real active STAFF shift, starts the demo shift at zero
without asking for an opening balance, and closes it automatically after all
cash payment/refund rows have been recorded. A rerun also closes a legacy demo
shift left open by an older seed version.

The fixture intentionally does not create fake SePay provider webhook events.
It uses canonical payment rows and the real journal/invoice services, so local
reconciliation cannot mistake demo rows for bank events.

This flag is for local QA only. Keep
`APP_SEED_DEMO_SCENARIOS_ENABLED=false` in production and never enable it
against the Neon production database.
