# Full-system QA test plan

Last updated: 2026-07-24
Repository: `hotelmanagement-new`
Baseline ref: `476f9c656c86af234706acbfff116530e0d5b7e3`

## 1. Objective

Verify the complete Hotel Management system from database constraints through
backend services and REST contracts to the customer and operations UIs. The QA
cycle is continuous:

1. establish a reproducible baseline;
2. map current behaviour and coverage;
3. add missing automated tests;
4. reproduce and register defects;
5. fix only defects with evidence;
6. run focused regression and then the complete regression suite.

Passing the existing suite is a baseline, not proof that the system is ready
for production.

## 2. Non-negotiable invariants

- PostgreSQL 16 is the only runtime database. MySQL instructions from older
  documents are replaced by PostgreSQL/Flyway/Testcontainers gates.
- Preserve current REST paths, DTO fields, database names and externally
  observable workflow unless a reproduced defect requires a compatible fix.
- Never accept payment twice, assign one physical room to two active stays,
  refund twice, checkout twice or create duplicate ledger effects.
- `Idempotency-Key` remains mandatory on sensitive mutations and retries must
  replay the original result without repeating side effects.
- Provider occurrence time, not webhook arrival time, determines whether a
  SePay payment was on time.
- Online payments and bank-transfer refund confirmation use SePay only.
  compatibility and is never selected for a new online payment.
- A deposit QR creates the RoomHold. Creating a reservation alone does not.
- Underpayment cancels/releases the hold and records a full refund obligation;
  overpayment accepts only the expected amount and records only the excess
  refund obligation.
- Late or additional transfers are recorded in the ledger but never revive a
  cancelled/expired booking.
- Reservation final state changes that depend on a refund happen only after the
  refund reaches its completed state.
- Checkout always recalculates reconciliation inside the checkout transaction.
  A read-only preview is never trusted as the final decision.
- An invoice snapshot is immutable after issue.
- Audit records are append-only; STAFF cannot read the ADMIN audit-log API.
- No real bank transfer, real refund or destructive production database
  operation is executed without an explicit, coordinated confirmation. Live
  OAuth/email checks may be used when the user authorizes the provider flow.

## 3. Test environments

| Environment | Purpose | Data policy |
| --- | --- | --- |
| Maven unit/integration profile | Fast backend regression on H2 PostgreSQL mode | Test-created, disposable |
| PostgreSQL 16 Testcontainers | Flyway, constraints, concurrency and dialect behaviour | Disposable container |
| PostgreSQL Docker + native backend/frontend | Cross-layer API/UI workflows without Dockerizing the app processes | Demo/local records only; snapshot before destructive scenarios |
| Chrome/Playwright test profile | Customer and operations UI workflows | Separate browser profile; no personal browser data |
| Production | Health/read-only checks only when explicitly authorized | No mutation during this QA cycle |

## 4. Execution waves

### Wave 0 — Baseline and inventory

- Capture branch/ref/dirty-worktree state without overwriting user changes.
- Verify Docker services, health endpoints and public frontend response.
- Read API, security, environment, Flyway and workflow documentation.
- Inventory backend controllers, role guards, tests and frontend routes/API
  callers.
- Run full backend tests, migration integration tests, frontend lint and a clean
  production build.

Exit criterion: baseline results and initial gaps are recorded before any fix.

### Wave 1 — Authentication and authorization

- Local registration, validation, email-verification token lifecycle,
  resend/expiry/reuse, login, refresh, logout and password reset.
- Google/Facebook internal callback, exchange-ticket and missing-email profile
  completion contracts using provider stubs; no live provider login required.
- ADMIN/STAFF single-session behaviour and CUSTOMER multi-session behaviour.
- Role matrix, ownership checks, STAFF audit denial and customer isolation.
- Rate limiting and safe error responses without account/credential leakage.

### Wave 2 — Catalogue and availability

- Facility, room type, room, gallery, media and contact CRUD authorization.
- Ordered facility images (maximum 2) and room-type images (maximum 3) at DTO,
  service and PostgreSQL constraint levels.
- Availability boundaries, arbitrary minute precision, multi-room-type
  quantities and concurrent capacity checks.
- Frontend loading, empty/error state and API contract alignment.

### Wave 3 — Reservation, RoomHold and payment

- Guest and authenticated reservations with one or several room types.
- Payment plans 50% and 100%.
- No hold before QR; one active hold per selected room-type allocation after
  deposit QR; convert/release/expire behaviour.
- SePay webhook authentication, timestamp tolerance, merchant-account
  validation, replay/deduplication and reconciliation after downtime.
- Exact payment, underpayment, overpayment, duplicate transfer, late transfer,
  QR abandon and timeout by payment purpose.
- Atomic ledger/event/refund updates and rollback on injected failure.

### Wave 4 — Operations lifecycle

- Staff confirm/reject and no-show conditions.
- Multi-room assignment by required room type and quantity.
- Check-in guest validation, representative guest and arbitrary check-in/out
  minute values.
- Atomic walk-in with CASH, SEPAY and UNPAID.
- Final payment, additional fee, refund obligations and reconciliation preview.
- Checkout match, mismatch handling, automatic reconciliation-request
  resolution and double-checkout prevention.
- Immutable invoice creation/printing contract.

### Wave 5 — Refund, audit and monitoring

- Customer cancellation recipient data and cancellation fee snapshots.
- Bank/QR refund pending → outgoing SePay completion, amount/code matching,
  idempotency, unmatched outgoing event and delayed manual fallback.
- Cash refund pending → explicit handover confirmation → completed.
- No reservation finalization while refund remains incomplete.
- Audit actor/action/target/correlation values for operational and management
  mutations; no invoice-print/login-noise actions.
- ADMIN-only audit API, append-only database trigger, risk rendering, monitoring
  counters and durable SendGrid outbox retries using a fake gateway.

### Wave 6 — UI and release regression

- Responsive customer journeys and dashboard workflows at desktop/tablet/mobile.
- Modal centering, escape/click-outside/focus behaviour, inline validation,
  loading/disabled/error/empty states and no horizontal overflow.
- Route/navigation, authenticated redirects and direct URL refresh.
- Frontend lint/build, backend full suite, PostgreSQL migration suite,
  PostgreSQL-container health and `git diff --check`. Backend/frontend Docker
  builds are optional deployment gates, not required for the low-resource local
  QA runtime.

## 5. Methods and minimum evidence

Each critical rule should be covered by at least two of:

- isolated unit test;
- Spring integration/MockMvc test;
- PostgreSQL Testcontainers test;
- API-level scenario;
- Playwright UI scenario;
- direct database invariant query after an API scenario.

Every registered bug must contain:

- reproducible precondition and steps;
- expected and actual behaviour;
- affected layer/workflow and severity;
- failing automated test or deterministic evidence;
- changed files;
- focused regression result;
- complete-suite regression result before it is marked fixed.

## 6. Priorities and stop conditions

Priority order:

1. security and money correctness;
2. database integrity, concurrency and migration safety;
3. reservation/operations correctness;
4. API/UI contract and accessibility;
5. visual/tooling polish.

Stop and ask for user action only when completion requires real payment/refund,
live OAuth/provider approval, real email delivery, production credentials,
production mutation, destructive data migration, an unresolved policy decision
or a backward-incompatible contract change. Tool limitations are recorded but
do not stop test work when an equivalent isolated test method exists.
