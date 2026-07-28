# Business statistics test matrix

## Automated gates

| Area | Required evidence |
|---|---|
| Period/timezone | default hotel date, UTC boundary, reverse/max range rejection, Monday week/month bucket |
| Formula service | recognized/cash separation, empty buckets, previous equal period, zero denominator, hourly occupancy |
| PostgreSQL query | clean Flyway V1-V24 and all report queries on PostgreSQL 16 |
| Money visibility | unmatched incoming shown; unclassified outgoing shown; matched refund out not doubled |
| Refund obligation | `CANCELLED` refund leaves uncovered payable amount |
| Revenue allocation | partial-stay period receives proportional room revenue |
| Reservation detail | invoice-period membership, status/code search, immutable breakdown, gross/accepted/refund/net cash and pagination |
| Authorization | ADMIN allowed; STAFF/CUSTOMER receive 403 for financial endpoints |
| CSV | BOM, formula neutralization, provider/search filters, seven report types, row cap header |
| Frontend | API helpers, unit tests, ESLint and production Next.js build |

Local execution on 2026-07-28: backend 448/448, PostgreSQL/Flyway 22/22,
frontend 40/40, ESLint pass and Next.js production build pass.

## Mandatory manual UAT

1. ADMIN opens today, last seven days and month-to-date; refresh and presets do
   not reset chosen granularity unexpectedly.
2. STAFF has no Statistics sidebar item and receives 403 from a direct API call.
3. Payment at 23:59/00:01 Vietnam time is assigned to the correct hotel date.
4. Cash payment and SePay payment appear under the correct provider filter.
5. Overpayment: gross cash includes the whole incoming amount, accepted cash
   includes only allocated money and refund outflow appears once after success.
6. Unmatched SePay incoming appears amber in warnings and ledger with its bank
   reference; it is never silently discarded.
7. SePay outgoing matched to a succeeded refund appears once as `REFUND_OUT`.
8. Unmatched outgoing appears amber as `UNCLASSIFIED_CASH_OUT`, not as a refund.
9. A cancelled refund is replaced/reopened operationally and the refund-payable
   KPI never drops merely because the row became `CANCELLED`.
10. A two-hour stay contributes two sold room-hours, not one full room-night.
11. Late actual check-in starts occupancy at actual check-in; checked-out stay
    ends at actual checkout.
12. Multi-room reservation multiplies sold hours by quantity and room-type rows
    remain separate.
13. Checkout creates one immutable invoice and recognized revenue appears once.
14. Empty period renders zero/empty states without errors or misleading NaN.
15. Search a reservation/invoice code in the reservation-revenue view; compare
    its immutable invoice, accepted payment, succeeded refund and net cash with
    the reservation detail.
16. Search the ledger by reservation code and provider reference.
17. Export all seven CSV files; Vietnamese text opens correctly in Excel/Sheets.

## Performance checks before production sign-off

- Run `EXPLAIN (ANALYZE, BUFFERS)` for overview cash, occupancy, room-type and
  ledger queries on a staging clone with realistic row counts.
- Verify a five-year request stays within the API timeout and a ledger export
  clearly marks truncation at 10,000 rows.
- Confirm V23/V24 indexes are used for the common 7-day and month-to-date ranges.
