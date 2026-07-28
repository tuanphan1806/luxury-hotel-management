# Business statistics implementation report

## Architecture

- `BusinessStatisticsController`: ADMIN-only HTTP and CSV boundary.
- `BusinessStatisticsService`: period validation, KPI formulas, empty-bucket
  filling, comparison and data-quality warnings.
- `BusinessStatisticsQueryRepository`: PostgreSQL read queries only.
- `BusinessStatisticsCsvService`: seven exports, 10,000-row row cap, UTF-8 BOM
  and spreadsheet-formula neutralization.
- `StatisticsPeriod` / `StatisticsGranularity`: one canonical timezone and
  bucket contract.
- Next.js `/dashboard/statistics`: responsive KPI, trend, room-type, warning,
  filter, pagination and export UI.

The implementation intentionally does not reuse operational Micrometer counters
as accounting data. It reuses canonical Reservation/Payment/Refund/Invoice
tables and the existing STAFF operations summary.

## Database changes

- V23 adds reporting indexes for invoice issue time, payment/refund completion,
  reservation creation/stay windows and room inventory history fields.
- V24 adds an expression/partial index for positive unlinked provider cash
  events using provider time with received-time fallback.
- Both migrations are expand-only: no financial row is updated or backfilled.

## Security and compatibility

- Every service method is protected with `hasRole('ADMIN')`; the controller is
  also protected at class level.
- STAFF and CUSTOMER authorization regression tests assert HTTP 403 for money
  endpoints.
- No REST contract, database column, reservation state transition, SePay
  webhook, refund completion, checkout or invoice mutation was changed.

## Financial safeguards added during review

1. Unmatched SePay cash in remains visible in totals, warnings and ledger.
2. Unclassified bank cash out remains visible but is not falsely called a
   refund.
3. A matched refund completion event is excluded from the provider-out branch,
   preventing double counting.
4. Cancelling a refund record cannot make the underlying obligation disappear.
5. Room-type revenue is allocated by stay overlap, consistent with occupancy.
6. Ledger time is rendered explicitly in the hotel timezone, independent of
   the ADMIN device timezone.
7. Reservation detail first pages immutable invoices and only then aggregates
   the related payment/refund rows, avoiding a full financial-table aggregate
   on every page request.
8. Reservation and ledger search are explicit submit actions, so typing does
   not trigger repeated financial queries.

## Deployment

1. Snapshot the PostgreSQL database.
2. Run Flyway through the normal backend deployment and confirm version V24.
3. Deploy backend and run ADMIN/STAFF authorization smoke tests.
4. Deploy frontend and perform the manual UAT matrix.
5. Reconcile one short period against invoices, SePay incoming/outgoing events,
   cash payments and refunds before relying on the dashboard operationally.

## Local verification evidence (2026-07-28)

- Backend regression: 448 tests, 0 failures, 0 errors, 0 skipped.
- PostgreSQL 16/Flyway profile: 22 tests, 0 failures; clean migration V1-V24.
- Business reporting PostgreSQL fixtures cover hotel-timezone boundaries,
  unmatched cash, unclassified outflow, refund obligation retention and
  proportional room-revenue allocation.
- Frontend Vitest: 40 tests across 8 files, all passed.
- ESLint: passed with exit code 0.
- Next.js production build: compiled, type-checked and generated 44 routes;
  `/dashboard/statistics` is included (15.5 kB route, 157 kB first load).
- `git diff --check`: no whitespace errors. Line-ending notices are existing
  Windows LF/CRLF normalization warnings, not diff defects.

Targeted browser UAT is complete for ADMIN access, day/week/month switching,
reservation-code search and per-reservation revenue reconciliation against the
local fixture data. Full operator UAT and query-plan measurement against
production-scale data remain rollout gates and are not represented as completed
by these local results.

## Deferred accounting scope

A read-only operational report is complete in this phase. Double-entry journal,
cashier shift close, expenses, period locking and manual accounting adjustment
remain deliberately deferred because they require new financial mutation
primitives and a separate audit/concurrency design.
