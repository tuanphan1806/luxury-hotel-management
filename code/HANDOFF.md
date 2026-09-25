## Task handoff

- Khi bắt đầu phiên mới, hãy đọc file `HANDOFF.md` nếu tồn tại.
- Trước khi kết thúc một tác vụ dài, hãy cập nhật `HANDOFF.md`.
- Không xóa nội dung bàn giao khi công việc chưa hoàn thành.

## Current release verification — 2026-08-14

### Deployment recovery — 2026-09-17 (in progress)

- The owner requests restoring the deployed site and explicitly requires Free
  plans. Do not upgrade Neon, Render, or Vercel or incur subscription charges.
- Vercel production and Render's last successful deployment use `ffc66eb`.
  Neon project `restless-boat-57677488`, production branch
  `br-aged-term-az9hd6tb`, is blocked by compute quota; Render startup logs
  show PostgreSQL SQLSTATE `53000` while Flyway obtains a connection.
- Disabled the GitHub **Keep Render backend warm** workflow through the
  authenticated UI; verified `Workflow disabled successfully` and
  `This workflow was disabled manually`. This prevents scheduled wake-ups
  after recovery. Cold starts remain expected on Render Free. No payment,
  RoomHold, reconciliation, or application scheduler was disabled.
- Neon console has Restart compute disabled. Usage/reset inconsistency needs
  investigation; no quota reset or database mutation has been performed.
  The official Neon CLI `4.21.0` is available through npx. The user approved
  CLI login and Windows keyring storage, but automatic approval review then
  rejected the OAuth consent: Neon requests account-wide project/org create,
  read, update, delete and org permission-management scopes. That broader
  temporary grant still needs explicit user approval; do not bypass it.
  Inspected CLI source: `profile create <name> --mint --project-id
  restless-boat-57677488 --keyring` retains only a project-scoped API key and
  attempts to revoke the temporary OAuth token in its finally block, but it
  still requests the same broad initial OAuth scopes. No such profile has
  been created. The earlier auth process expired; a fresh flow is required
  only after the scope approval. All service plans must remain Free.
- Details and recovery limitations are in
  `output/deployment-review-2026-09-17/review.md`. Website recovery is NOT
  complete; verify database access, Render JSON health UP, proxied public
  APIs and appropriate user flows after quota access is restored.

- Current source verification is recorded in
  `docs/qa/release-readiness-2026-08-14.md`. It supersedes the test counts in
  the historical sections below without rewriting their point-in-time
  evidence.
- Backend verification: 717 tests passed with no failure/error/skip; the
  PostgreSQL 16 Testcontainers gate applied Flyway V1 through V39 and passed
  Hibernate validation, migration, idempotency and concurrency checks. JaCoCo
  enforces 65% line/45% branch coverage; the current result is 71.03%/53.02%.
- Frontend verification: full dependency audit, ESLint, TypeScript, 108 Vitest
  tests, 70% deterministic-library coverage thresholds, 10 accessibility
  browser scenarios, six chatbot browser scenarios and the 47-route Next.js
  production build passed.
- CI now retains CycloneDX 1.6 SBOM evidence (176 components), rejects newly
  introduced High/Critical vulnerabilities and AGPL dependencies, and runs a
  full OSV source scan plus a High/Critical Trivy scan of the built backend
  container.
- The `nanoid` production advisory found during the 2026-08-14 audit was
  remediated by moving the enforced transitive version from 3.3.17 to 3.3.18.
- The owner explicitly keeps demo credentials and accepts Free-tier capacity
  limits for controlled testing. Before unrestricted public real-money
  go-live, credentials/session rotation, STAFF operator UAT, real-provider
  SePay/SendGrid evidence and a current Neon restore/RPO/RTO rehearsal remain
  mandatory external gates.

## Production release candidate — 2026-07-26

- Release-readiness refresh 2026-08-02 on branch
  `fix/release-readiness-20260802`: backend/Flyway PostgreSQL gate passes 522
  unit tests plus 27 integration tests; frontend passes ESLint, explicit
  TypeScript checking, 71 unit tests and a 47-route production build.
- Vercel Native Deployment Checks `Lint` and `Typecheck` are configured as
  blocking production promotion checks. GitHub ruleset is prepared with
  required `Branch policy`, `Backend and PostgreSQL 16`, `Frontend` checks and
  up-to-date branches; GitHub still requires the repository owner to complete
  the open re-authentication dialog before the ruleset save is durable.
- Booking keeps the action available and shows an explicit inline error when
  terms have not been accepted. Reservation search
  preserves an invalid check-out value, explains the validation failure and
  clears stale availability instead of silently restoring defaults.
- Chatbot public FAQ no longer performs unconditional catalog/N+1 review
  requests, has bounded internal/Gemini timeouts and returns deterministic
  room-package pricing for common room-tier questions. Production previously
  reproduced a ~57-second 502; deploy and repeat the same browser check before
  marking the live chatbot fixed.
- Production auth remains first-party through Vercel `/backend_proxy`, so
  `Secure` + `SameSite=Lax` is the canonical cookie topology. `None` is only
  valid if the browser is deliberately changed to call Render cross-site and
  the entire login/refresh/logout/OAuth matrix is rerun.

- Production verification was refreshed on 2026-07-30 at deployed ref
  `87e9b3c`: ADMIN/STAFF role boundaries, cash walk-in/check-out, invoice,
  audit and financial journal were checked directly in the deployed UI.
- Reservation `RES-18557480` is the cash UAT evidence: 70,000 VND is identical
  across payment, checkout reconciliation, invoice, `CASH_IN`,
  `REVENUE_RECOGNIZED` and audit trail.
- The live finance dashboard and checkout-exception page currently show zero
  unresolved cash-flow/checkout exceptions. The earlier 2,000 VND SePay item
  is no longer in the active review queue.
- A bounded 24-request read-only smoke returned HTTP 200 throughout (p95 home
  1,625 ms, rooms 560 ms, backend health 278 ms). Keep the production-like
  load gate PARTIAL until it is run against an approved staging/Neon clone.
- Neon restore rehearsal is complete on child branch
  `pre-go-live-20260730`: reset from `production` succeeded and all ten
  `post-cutover-validate.sql` statements passed, including FK, identity,
  constraint and 28-migration checks. The expiring clone auto-deletes on
  2026-07-31 at 00:42 GMT+7.
- Remaining release evidence: coordinated real-bank SePay incoming/outgoing,
  sustained staging load, monitoring-email receipt and final operator
  sign-off.

- Active checkout remains `C:\Users\admin\Downloads\hotelmanagement-new`; the
  OneDrive checkout is not the release source.
- Backend SOLID refactor preserves the existing REST/database contracts and
  reservation, RoomHold, SePay, refund, ledger, check-in and checkout ordering.
- VNPay runtime code/config has been removed. PostgreSQL migration V11 fails
  closed if unsupported provider history exists, removes VNPay-only columns and
  constrains payment/refund/provider-event data to the supported SePay/CASH
  contracts. The production Neon preflight was confirmed to contain no VNPay
  history.
- Final local release evidence: 321 backend tests, eight PostgreSQL migration
  tests (fresh/upgrade/idempotency/legacy rejection), nine frontend unit tests,
  16 browser E2E scenarios, frontend lint/build (42 routes) and the backend
  Docker image build all pass.
- Render production remains on the Free Singapore service and Vercel on the
  Hobby project. Required Neon/JWT/OAuth/Cloudinary/SendGrid/SePay/bank and
  frontend proxy variables are present and masked in their dashboards.
- Remaining external evidence is intentionally tracked as PARTIAL in
  `docs/qa/full-system-test-report.md`: SendGrid inbox deliverability and plan
  continuity, a coordinated real SePay incoming/outgoing transfer, production
  load/operator UAT and monitoring alert receipt. Neon restore validation is
  complete.

## PostgreSQL database cutover — 2026-07-19

- Runtime database support is PostgreSQL-only: PostgreSQL JDBC/Flyway modules,
  `application.yml`, `.env.example`, local `.env`, and Docker Compose all use
  `jdbc:postgresql`.
- Active Flyway location is `classpath:db/migration-postgres`. V1 is the
  consolidated PostgreSQL baseline (34 application tables); V2 adds
  workload-aligned indexes/data checks and removes seven redundant indexes
  covered by new composite prefixes; V3 normalizes the boolean default without
  changing the V1/V2 checksums.
- Local Compose publishes PostgreSQL on host port `5433` because this Windows
  machine already has a listener on `5432`; container-to-container traffic
  remains `postgres:5432`. H2 is test-scope only and is absent from the runtime
  jar; the unused `pgcrypto` extension was removed from the baseline.
- No alternate database migration directory is included or scanned by the
  application; Flyway has one PostgreSQL source of truth.
- PostgreSQL Testcontainers gates cover clean V1→V3, Hibernate
  `ddl-auto=validate`, PostgreSQL type/index/constraint assertions, imported-ID
  identity reseeding, native/enum queries, and 10-way idempotency concurrency.
  Current evidence: 208 normal tests and four PostgreSQL migration tests pass.
- PostgreSQL backup, validation and rollback boundaries are documented in
  `docs/database/postgresql.md`. Do not reopen writes before backup, row/
  financial reconciliation and operator UAT.

## Payment platform compatibility release — 2026-07-18

- Repository hiện ở `C:\Users\admin\Downloads\hotelmanagement-new`.
- Implemented the PostgreSQL V1/V2/V3 schema and PostgreSQL Testcontainers gates.
- Implemented allocation/refund ledgers, durable SePay dedup/retry/review,
  idempotency, reconciliation cursor, inventory metadata/locking, purpose-aware
  expiry, audit/invoice v2, atomic walk-in and refund cancellation/reactivation.
- Completed canonical financial UTC dual fields, normalized invoice snapshot,
  merchant-account webhook rejection, mandatory idempotency for financial and
  operational mutations, PDF refund proof, no-show guard and concurrency tests.
- Idempotency hardening now commits claim + domain mutation + completion in one
  transaction, canonicalizes JSON payload hashes and retries/replays unique
  conflicts and transient transaction deadlocks. `POST /api/reservations` now also
  requires the key; guest create derives a SHA-256 capability from it. Booking
  page and chatbot reuse the same key for network retries.
- Reservation cardinality remains one reservation to many RoomTypes and many
  physical rooms. The lock invariant is only that one physical Room cannot be
  assigned to two active overlapping stays.
- V29 adds SePay outgoing confirmation for QR refunds: API-key authentication,
  exact `refund_code + expected_amount` matching, durable replay protection,
  time-gated manual fallback and a single refund-completion finalizer shared
  with cash handover.
- Verification at handoff: backend 208/208 tests passed; frontend production
  build passed; PostgreSQL V1→V3, Hibernate schema validation, local sequence
  finalization and post-cutover validation passed.
  Local Spring web startup against PostgreSQL also returned HTTP 200 from
  `GET /actuator/health`. `IdempotencyRequest.requestHash` and the invoice
  `currency` / `snapshotHash` mappings are explicitly aligned with Flyway
  `CHAR` columns.
- Dedicated H2 and PostgreSQL idempotency gates send 10 concurrent requests
  with one key and assert all callers receive the same resource while the
  action runs exactly once.
- Do not remove compatibility columns, legacy endpoints/status aliases or the
  existing `(provider, provider_reference)` unique key without a separately
  approved contract migration.
- Remaining production rollout gates: run `post-cutover-finalize.sql` then
  `post-cutover-validate.sql` against a PostgreSQL backup/staging clone,
  configure secrets and merchant account values, then complete
  concurrency/load and operator UAT.
- Local `.env` now includes the PostgreSQL pool/Flyway retry settings. The
  existing `SEPAY_WEBHOOK_SECRET` remains a compatibility alias for
  `SEPAY_WEBHOOK_API_KEY`; before deployment, move to the canonical variable and
  configure the exact same value in SePay, never writing it to docs/logs.
- Local and ngrok provider-test probes both returned HTTP 200 with
  `{"success":true}` on `/api/payments/sepay/webhook`. The ngrok inspector also
  recorded the public POST as 200. Online UI exposes SePay VietQR only.
- Dev profile disables DevTools persistent HTTP sessions and uses target-local
  Tomcat directories to avoid Windows `ApplicationTemp` ownership failures.
- The opt-in `postgres-migration-test` profile filters `target/classes` for the test
  profile. If a dev process with DevTools is already running, finish the gate by
  rebuilding `mvn -Pdev -DskipTests package`; otherwise the live process may
  temporarily reload test webhook credentials and return 401.
- Báo cáo hợp nhất: `docs/payment-platform/consolidated-implementation-report.md`.

### Neon CLI consent — 2026-09-18 follow-up (superseded by result below)

- The owner explicitly approved the temporary account-wide OAuth scopes and
  project-scoped keyring profile. The command was accepted, but automated
  approval review still rejected clicking Authorize, requiring action-time
  confirmation. The owner was asked to click the Chrome consent directly.
- Two authorized CLI attempts for profile `hotel-recovery-20260918` timed out
  after the CLI's fixed 60-second wait. No successful OAuth callback or
  project-scoped key creation has been reported. Do not treat the open stale
  consent tab as an active login.
- Next step: coordinate a fresh 60-second login window with the owner, who
  must click Authorize directly. Do not automate that blocked click or
  assume earlier failed flows left a usable credential. Keep all plans Free.

### Neon recovery — 2026-09-18 latest result

- CLI authentication succeeded. Profile `hotel-recovery-20260918` holds a
  project-scoped organization API key in Windows keyring, limited to
  `restless-boat-57677488`. The mint flow reported signing the OAuth session
  back out. Always supply `--profile hotel-recovery-20260918` to API calls.
- An API `--describe` call accidentally omitted the profile and triggered
  another OAuth login. Removed DEFAULT with the official profile command;
  CLI confirmed OAuth token revoked. It left credentials.json on disk
  because it considered that file not created by neon; the token is revoked.
- GET project: period 2026-09-01 through 2026-10-01; compute_time_seconds
  396670; last active 2026-08-17T23:58:28Z; no customer quota in settings.
  GET endpoint: idle, disabled=false, no pending state, autoscale 0.25–2 CU.
- POST start on the existing compute failed: compute time quota exceeded,
  usage 396670, limit 396000. No compute was started.
- Proposed documented PATCH sets only
  project.settings.quota.compute_time_seconds=0 to remove a project-set cap
  and try clearing stale suspension. Automatic approval review rejected it
  before execution, requiring explicit permission for this production
  setting. User question is pending; no quota has been changed. It cannot
  increase platform Free allowances, and recovery is not guaranteed.
- Keep Free plans, keep warm workflow disabled, preserve all database data.

### Neon recovery — quota change result, 2026-09-18

- User explicitly approved compute project quota=0. PATCH succeeded; GET
  confirmed settings.quota.compute_time_seconds=0 and the September period.
- POST start after PATCH still fails: usage 396670, limit 396000. Platform
  quota remains blocked. Do not repeat quota mutations or imply recovery.
- Report and sanitized evidence: output/deployment-recovery-2026-09-18/.
  neon-support-draft.txt is prepared but NOT sent. Permission to send a
  possibly public Neon community request is pending. Keep all plans Free.
- New evidence supersedes the earlier pending-quota-approval entry above.

### Free optimization and data preservation — 2026-09-19

- Latest requirement: preserve all old data; supersedes earlier fresh-demo approval.
- UptimeRobot monitor 803563178 confirmed calling Render health every 5 minutes;
  paused in UI. GitHub keep-warm workflow remains disabled. Local workflow edit
  removes scheduled triggers and retains manual dispatch; not pushed.
- Old Neon compute autoscaling saved as 0.25–0.5 CU with five-minute suspend.
  Official CLI GET/start on Sep 19 still yields quota 396670 > 396000;
  period Sep 1–Oct 1, last active Aug 17. No database recovery or export yet.
- Render saved DB_KEEPALIVE_TIME_MS=0 and
  SPRING_DATASOURCE_HIKARI_IDLE_TIMEOUT=60000 using Save only. Existing pool=5,
  min-idle=0 verified. Runtime application waits for successful deployment.
  Local render.yaml mirrors these values; not pushed. Never change datasource
  to an empty demo DB, disable financial jobs, or imply health is restored.
- Vercel Functions region saved sin1; same-source production redeploy verified
  Ready on Sep 19: BSjpCekfk2txfNzH6SaDcsDABPyh / ja85i9s67 domain.
- Earlier fresh-demo approval resulted in empty Free Neon long-mode-03386743
  (luxury-hotel-demo, PG16 Singapore). No credentials switched or data seeded;
  unused following the user's preservation request. Old DB untouched.
- Detailed evidence and pending recovery steps:
  output/deployment-recovery-2026-09-18/optimization-2026-09-19.md.

### Fresh Neon cutover — 2026-09-22 (supersedes September 19 preservation gate)

- User explicitly requested continuing with a new database. Preserve the old
  Neon project but use the existing Free luxury-hotel-demo / long-mode-03386743.
- Submitted Render Save and deploy, dep-dap40180cd8s73blt7s0, existing ffc66eb
  build with new direct TLS datasource. Initial state: Spring Boot starting.
- Master catalog seed temporarily enabled; weak demo-user seed remains false.
  Historical SePay reconciliation temporarily disabled for this empty database;
  webhook authentication preserved. No old data migrated or deleted.
- Follow current evidence/status in
  output/deployment-recovery-2026-09-18/recovery-2026-09-22.md.

### Neon demo recovery and Cloudinary — 2026-09-23

- New Neon connection works: PG16.15, Flyway V1–V39 applied successfully.
  Old project remains untouched; old accounts/bookings were not migrated.
- Render startup initially timed out. Added TieredStopAtLevel=1 and
  ActiveProcessorCount=1 to existing JVM memory flags. Same ffc66eb build then
  started in ~150 seconds; dep-dapbd4nf3r2c73c46vq0 became Live.
- User requested every demo seed and explicitly approved unchanged weak demo
  accounts after the concrete admin/123456 risk was explained. Seed verified:
  6 room types, 18 rooms, 10 users, 28 reservations, 60 reviews; logs confirm
  26 payments, 3 refunds, 20 invoices and 49 journal entries, all demo fixtures.
- Corrected SEED_MEDIA_BASE_URL from Render to the existing Cloudinary folder.
  Targeted migration uploaded 5 missing add-on images; 96/96 seed assets now
  HEAD 200. Rooms list/detail load Cloudinary images. Demo admin UI login passed.
- All five data seed flags and static image migration disabled, read back false.
  Final deploy dep-dapbhd8473hc738t5cc0 Live at Sep 23 00:22:24 GMT+7;
  startup 134.105 seconds. Direct and proxied health=UP, Vercel catalog HTTP 200.
  Do not roll back to an environment with the one-time seed flags enabled.
- SePay reconciliation remains disabled for this fresh demo DB; authentication
  secrets retained. No real-payment certification. UptimeRobot and scheduled
  GitHub wake-ups remain paused/disabled per prior verification.
- Local render.yaml mirrors JVM/pool/reconciliation settings, not pushed.
  Detailed deployment IDs, results and outstanding checks are in the report.

### Chatbot review and regression fixes — 2026-09-23

- The 17 failing ChatBotService tests used August 2026 fixtures against the
  wall clock. Injected a Clock for deterministic tests; runtime stay parsing
  and past-date checks now consistently use Asia/Ho_Chi_Minh.
- Fixed ISO/local date overlap, reuse of one time for two dates, invalid-date
  corrections retaining the old stay, and hyphenated check-in/check-out fields.
- ChatWidget invalidates old booking confirmation after a new turn, honors the
  backend's cleared state, recognizes Vietnamese “đồng ý”, and tolerates blocked
  sessionStorage. DTO list elements reject null values.
- Verification: full backend 673/673, frontend chat unit 13/13, lint and
  production build pass; chatbot Playwright 12/12 on local production build
  across desktop/mobile with mocked API. Initial dev run was blocked by the
  Next Dev Tools indicator overlapping the chat button; see report.
- Existing local Gemini key accepted model-metadata GET (200); no live model
  generation or inference quota validation. This does not establish Render
  Gemini configuration. No production deployment of these changes in this turn.
- Details and evidence: output/chatbot-review-2026-09-23/review.md.

### Production completion candidate — 2026-09-24

- Added bounded retries for read-only public catalog GETs (3 attempts), a delayed
  connection notice and manual reload on persistent failure. Rooms distinguish
  failed loading from an actually empty catalog. Booking/payment mutations are
  never retried by this helper. No recurring wake-up was introduced.
- Patched Next.js/eslint-config-next to resolved 15.5.26, sharp 0.35.4,
  fast-uri 3.1.6, js-yaml 4.3.2, browserslist 4.28.7, and Vitest/coverage 4.1.11.
  Final pnpm audit reports zero known vulnerabilities; no audit exclusions.
- Frontend unit/coverage: 122 tests pass; statements 81.16%, branches 73.13%,
  functions 80.58%, lines 85.60%. Lint, TypeScript and production build pass.
  Added upload validation/response contract tests and catalog recovery CI gate.
- Read-only Neon production audit: 6 room types, 18 rooms, 11 users, 28 bookings,
  60 reviews, 26 payments, 3 refunds, 20 invoices, 49 journals; eight integrity
  checks return zero violations. Old database retained; no seed/data mutation.
- Gemini key transfer to Render explicitly approved, but Chrome file upload
  is blocked until the extension has Allow access to file URLs enabled.
  Existing key model-metadata check is not proof of inference quota/delivery.
- Email remains blocked by expired SendGrid trial; user has not selected or
  supplied another active account. No additional email or bank transfer sent.
- Source release and provider deployment status must be checked separately.
  Evidence directory: output/production-completion-2026-09-24 (not committed).
- Browser verification: chatbot/catalog 16/16 pass; accessibility 10/10 pass after waiting for entry animations before measuring contrast. Desktop/mobile screenshots reviewed.

### Runtime recovery verification — 2026-09-25

- Previous release e8471d2 is live on Vercel and Render. Gemini configuration
  was completed and actual English/Vietnamese model responses were verified.
  SendGrid remains expired; user explicitly deferred email until another active
  provider is available. Old Neon data remains preserved, not migrated.
- Isolated PostgreSQL runtime tests found two defects: persisted JSON numeric
  node types caused unchanged quotes to fail PRICE_CHANGED; loading a rate for
  delete-audit left a managed child referencing a removed unused room type.
  Fixes compare exact numeric values only at commitment validation and detach
  the audit-only rate before the existing database cascade. Quote hashes,
  historical-data deletion guards, migrations and financial policy are unchanged.
- Added compose.qa.yml, guarded Playwright runtime configuration, synthetic
  staff-shift setup, and a dump/restore verification script. This environment
  has its own volume, internal backend network and no real provider credentials.
  See docs/qa/isolated-runtime.md. Never use the ordinary shared Compose DB.
- Local verification: 677 backend tests and frontend production build pass;
  PostgreSQL migration/concurrency suite passed 29 tests. All eight isolated
  runtime tests pass, including signed webhook replay/underpayment/overpayment
  and outgoing refunds. QA dump/restore matches counts and row digests for all
  60 public tables; FK/identity/check validation passes. This is synthetic QA,
  not a real-bank transfer or a measured production recovery time.
  Release PR: https://github.com/tuanphan1806/luxury-hotel-management/pull/158.
  Deployment evidence is tracked in output/isolated-qa-2026-09-25/.
- Neon production backup: manual snapshot created 2026-09-25 14:04:38 UTC;
  console reports no expiry and one snapshot limit on the unchanged Free plan.
  Branch history restore window is six hours. No production restore performed.
