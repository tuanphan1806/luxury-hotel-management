# Business statistics specification

## Goal and boundary

Provide ADMIN with an operational finance view of reservations, recognized
revenue, real cash movement and room utilization. The module is read-only. It
must never create a payment, complete a refund, change a reservation, bypass
SePay, alter an invoice snapshot or override checkout reconciliation.

This is not the existing Micrometer/system monitoring feature and not a full
general ledger. `BusinessMetricService` and `BusinessMonitoringService` remain
responsible for application health; this module reads canonical business data.

## Roles

- `ADMIN`: all `/api/admin/statistics/**` endpoints, dashboard and CSV exports.
- `STAFF`: existing money-free `/api/operations/summary`; no financial DTO or
  direct statistics API access.
- `CUSTOMER`: no access.

Backend `@PreAuthorize` is authoritative. Sidebar visibility is only UX.

## Reporting clock

- Business timezone: `Asia/Ho_Chi_Minh`.
- `from` and `to` are inclusive hotel dates; the SQL range is `[from 00:00,
  to + 1 day 00:00)`.
- UTC financial instants are converted to hotel local time before truncating.
- Reservation stay fields are PostgreSQL local timestamps and are not converted
  a second time.
- Week starts Monday. Supported granularity: day, week and month.
- Default period: latest 30 hotel dates. Maximum period: five years.
- Previous-period comparison uses the immediately preceding range with the same
  number of inclusive dates.

## Canonical sources

| Concept | Source of truth | Recognition time |
|---|---|---|
| Recognized revenue | immutable `reservation_invoices` | `issued_at_utc` |
| Matched cash in | accepted `payment_transactions` | `paid_at_utc` |
| Unmatched bank cash in | positive unlinked provider `in` event | provider time, received time fallback |
| Completed refund out | `payment_refunds` status `SUCCEEDED` | `completed_at_utc` |
| Unclassified bank cash out | provider `out` event not completing a succeeded refund | provider time, received time fallback |
| Booking count | `reservations` | local `created_at` |
| Room usage | committed/actual reservation stay overlap | each hotel date |

Current RoomType prices must never rewrite old revenue. Invoice snapshots are
the historical monetary source.

## Required views

1. Overview: recognized revenue, booking count, hourly occupancy, ADR, RevPAR,
   previous-period changes, cash, receivables, deposits and refund payable.
2. Revenue series: room, add-on, additional fee, late checkout, other revenue,
   discount, tax and invoice count.
3. Cash-flow series: matched and unmatched cash in, accepted amount, completed
   refunds, unclassified outflow, canonical net cash and observed bank movement.
4. Booking series by current status.
5. Hourly occupancy series with room-night equivalents, ADR and RevPAR.
6. Room-type performance.
7. Paged reservation-revenue detail: immutable invoice breakdown, lifetime
   accepted/gross payment, succeeded refund and net cash for each reservation;
   filterable by reservation/invoice code and reservation status.
8. Paged read-only ledger containing matched cash, unmatched cash, refunds,
   unclassified outflow and recognized invoices; searchable by reservation or
   provider reference.
9. UTF-8 CSV for all seven detailed views.

## Data-quality rules

- Money is never silently dropped. Unmatched incoming and unclassified outgoing
  provider events are explicit amber `REVIEW_REQUIRED` ledger rows.
- A provider `out` event linked as the completion event of a succeeded refund is
  represented by the refund only, preventing double counting.
- Legacy payments without `received_amount` are reported separately and not
  guessed from `amount` as canonical cash.
- `PaymentRefund.CANCELLED` never removes a mandatory refund obligation.
- Historical occupancy is marked estimated because the schema has no daily
  maintenance/inventory snapshot.
- Zero denominators return zero, never `NaN`, infinity or a server error.

## Non-goals for this release

Chart of accounts, balanced journal entries, cashier shifts, cash-drawer
variance, expenses/AP, accounting period lock, business-day close/reopen and
manual financial adjustment are a separate reviewed accounting phase.
