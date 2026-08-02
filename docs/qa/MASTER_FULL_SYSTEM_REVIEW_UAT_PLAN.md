# Kế hoạch review, kiểm thử và sẵn sàng vận hành toàn hệ thống

> Tài liệu điều phối cấp cao nhất cho đợt review toàn diện Hotel Management.  
> Ngày lập: 2026-08-02  
> Repository: `C:\Users\admin\Downloads\hotelmanagement-new`  
> Baseline lúc lập kế hoạch: nhánh `fix/release-readiness-20260802`, commit `d7b876af19f797f1ceba6a18e83a4ff80381ee12`  
> Trạng thái tài liệu: **PLAN ONLY — chưa thực thi review/fix/UAT theo tài liệu này**

## 1. Mục tiêu

Review và kiểm thử toàn bộ sản phẩm từ yêu cầu nghiệp vụ đến vận hành production, tìm lỗi có bằng chứng, sửa theo từng wave có regression, rồi đưa ra quyết định `GO / CONDITIONAL GO / NO-GO` có thể kiểm chứng.

Phạm vi gồm:

- nghiệp vụ, state machine và mọi workflow của CUSTOMER, STAFF, ADMIN, SYSTEM;
- backend, REST API, integrations, scheduler và kiến trúc Java/Spring Boot;
- frontend public, dashboard, responsive/mobile, accessibility, localization và SEO;
- PostgreSQL, Flyway, dữ liệu, seed, transaction, locking, index, backup và restore;
- security, privacy, audit trail, dependency, license và kiểm thử lạm dụng;
- unit, integration, contract, E2E, UAT, migration, concurrency, load, stress, soak, resilience và chaos;
- GitHub, Vercel, Render, Neon, Docker, Cloudinary, SendGrid, SePay, OAuth, UptimeRobot/Sentry;
- observability, incident response, DR, release, rollback, chi phí và rủi ro nhà cung cấp.

Không được coi “test hiện có đều xanh” là bằng chứng toàn hệ thống đã hoàn thiện. Mọi kết luận phải gắn với commit, môi trường, dữ liệu và artifact cụ thể.

## 2. Giới hạn của giai đoạn lập kế hoạch

Trong lượt lập kế hoạch này:

- chỉ thêm tài liệu này;
- không sửa code, cấu hình runtime, secret, migration hay dữ liệu;
- không chạy giao dịch ngân hàng, refund, gửi email thật, load test production hoặc thao tác phá hủy;
- không dùng số liệu test cũ để khẳng định trạng thái hiện tại nếu chưa chạy lại trên ref được khóa.

Khi bắt đầu thực thi, mỗi thay đổi phải đi qua chu trình:

`Reproduce → Ghi bằng chứng → Viết/điều chỉnh test → Fix tối thiểu → Focused regression → Full regression → Review diff → Deploy có kiểm soát → Verify`.

## 3. Nguyên tắc an toàn và bất biến bắt buộc

### 3.1. An toàn dữ liệu và môi trường

- Không xóa/reset production, Neon branch chính, Docker volume hoặc dữ liệu người dùng nếu chưa có phê duyệt riêng cho đúng thao tác.
- Load/stress/chaos/fuzz và migration phá hủy chỉ chạy trên database disposable hoặc staging clone.
- Tạo snapshot/restore point trước UAT có mutation; ghi lại cách cleanup dữ liệu thử.
- Không in secret, token, số tài khoản đầy đủ, dữ liệu nhận refund hoặc PII vào log/artifact.
- Không dùng tài khoản cá nhân trong test tự động; dùng test identity và browser profile riêng.
- Giao dịch SePay thật chỉ dùng một kịch bản giá trị thấp được điều phối, có mã theo dõi và kế hoạch hoàn/đối soát.

### 3.2. Bất biến nghiệp vụ/tài chính

- Không double-book một physical Room và không double-assign phòng trong cùng khoảng ở.
- Không double-charge, double-ledger, double-refund, double-check-in hoặc double-checkout.
- Một reservation có thể gồm nhiều room type và số lượng; lúc check-in phải gán đúng loại và đủ số phòng.
- RoomHold chỉ được tạo tại bước tạo deposit QR theo contract hiện hành; hold phải convert/release/expire đúng một lần.
- `Idempotency-Key` và provider-event dedup phải ngăn side effect lặp lại khi retry/concurrency.
- Provider occurrence time quyết định đúng hạn, không dùng thời điểm webhook đến để revive booking sai.
- Underpayment, overpayment, late payment, duplicate transfer và unmatched transfer phải vào ledger/queue đúng nghĩa; không làm mất tiền.
- Reservation chỉ chuyển trạng thái cuối phụ thuộc refund sau khi refund `COMPLETED`.
- QR/bank refund qua SePay outgoing và cash refund đều đi qua một điểm chốt completion; điều kiện hoàn tất khác nhau nhưng không có đường tắt.
- Checkout phải preview và tính lại reconciliation trong transaction; thiếu nghĩa vụ thanh toán/refund thì không checkout.
- Invoice snapshot đã phát hành là bất biến.
- Audit log append-only; STAFF không đọc API audit ADMIN.
- Giá giờ/qua đêm/ngày, phụ thu khách thêm, add-on booking-time/in-stay, phí thực tế và snapshot phải nhất quán xuyên suốt quote → reservation → payment → invoice → reporting.

## 4. Mô hình trạng thái kết quả

Mỗi hạng mục bắt buộc có đúng một trạng thái:

| Trạng thái | Ý nghĩa |
| --- | --- |
| `PASS` | Đã kiểm chứng ở ref/môi trường hiện tại, có artifact và kết quả mong đợi rõ ràng |
| `PARTIAL` | Một phần đã có bằng chứng, còn nhánh/môi trường/tình huống chưa kiểm chứng |
| `FAIL` | Đã tái hiện sai lệch so với contract hoặc tiêu chí chấp nhận |
| `MISSING` | Chưa có implementation, test, tài liệu hoặc control cần thiết |
| `BLOCKED` | Không thể tiếp tục vì thiếu credential, quyết định nghiệp vụ, hạ tầng hoặc quyền |
| `N/A` | Không thuộc mô hình hiện tại; phải ghi lý do và điều kiện làm nó trở nên áp dụng |

Severity:

| Mức | Tiêu chí |
| --- | --- |
| `P0` | Mất/sai tiền, mất dữ liệu, bypass auth, lộ secret/PII nghiêm trọng, double-book hoặc production unavailable |
| `P1` | Workflow cốt lõi sai/không dùng được, migration/rollback không an toàn, lỗi bảo mật cao, không thể release |
| `P2` | Lỗi chức năng/UX/hiệu năng đáng kể nhưng có workaround an toàn |
| `P3` | Cải thiện nhỏ, debt, polish hoặc tài liệu không chặn vận hành |

Không được đóng finding chỉ bằng mô tả “đã sửa”. Cần test tái hiện trước fix hoặc bằng chứng deterministic tương đương, focused regression, full regression và xác minh sau deploy nếu ảnh hưởng production.

## 5. Baseline phải khóa trước khi review

Tạo `Review Manifest` gồm:

- repository tuyệt đối, branch, commit SHA, dirty worktree, remote và PR/release liên quan;
- Java/Maven, Node/pnpm, Docker, PostgreSQL, Chrome/Playwright versions;
- Spring/Next/React/Flyway/Testcontainers versions từ source of truth;
- migration hiện có và checksum; số controller/route/test phải đo lại, không chép số cũ;
- profile/env đang dùng, feature flags và trạng thái seed;
- deployment ID/commit trên Render và Vercel; Neon project/branch/region; GitHub ruleset/checks;
- time zone hệ điều hành, JVM, DB, browser và business zone `Asia/Ho_Chi_Minh`;
- tập dữ liệu thử, test accounts, reservation/payment/refund IDs được tạo;
- thời điểm bắt đầu/kết thúc, người thực hiện và link artifact.

Baseline kỹ thuật quan sát khi lập kế hoạch (phải xác minh lại lúc chạy): Java 17; Next.js 15/React 19; PostgreSQL 16; Flyway `V1`–`V32`; GitHub CI có backend/PostgreSQL và frontend gates; repository có 5 Playwright spec file và hơn 100 backend test source file.

## 6. Bản đồ hệ thống phải lập

### 6.1. Inventory code và dependency

- Module/package/class/component; owner và trách nhiệm.
- Controller → service → repository → DB/integration dependency graph.
- Package cycles, forbidden dependencies, direct instantiation, static/global state.
- REST endpoints, method, DTO, auth/role, idempotency, rate limit, pagination, caller frontend.
- Scheduler/background job, cron/interval, lock, retry, timeout, idempotency và failure policy.
- Frontend routes, layouts, server/client components, proxy routes, auth guards, data fetch strategy.
- External dependencies, version, license, CVE, purpose, replacement/exit plan.
- Assets/media/fonts, storage owner, cache/CDN policy và fallback.

### 6.2. Architecture và data flow

Phải có sơ đồ và bảng cho:

- browser → Vercel → same-origin `/backend_proxy` → Render → Neon;
- auth thường, refresh-cookie, Google/Facebook OAuth và email verification/reset;
- search/quote → reservation → QR → RoomHold → SePay webhook/reconciliation → confirmation;
- assignment → check-in → in-stay add-on/fee/payment → reconciliation → checkout → invoice;
- cancellation → refund obligation → cash/SePay outgoing → reservation finalization;
- audit/outbox/email/monitoring;
- work schedule → WorkShiftSession → CashierShift → cash transaction attribution;
- financial cash-flow report, earned revenue, invoice and business-day semantics.

Mỗi data flow ghi trust boundary, source of truth, transaction boundary, retry point, duplicate-prevention key và recovery path.

### 6.3. Quyết định kiến trúc cần xác minh

- Modular monolith hiện tại có phù hợp quy mô một khách sạn không.
- Layered/SOLID boundaries sau refactor; orchestrator lớn và transaction scope.
- API versioning: giữ compatible endpoint hiện tại hay đưa policy versioning cho thay đổi tương lai.
- In-memory auth rate limiter chỉ được chấp nhận khi một instance; multi-instance phải dùng shared store.
- Single points of failure: Render, Neon, SePay, SendGrid, Cloudinary, OAuth providers.
- Multi-tenancy mặc định `N/A`; nếu mở nhiều khách sạn phải thiết kế tenant isolation trước khi lưu tenant thứ hai.
- Kubernetes/native mobile/offline mode chỉ `N/A` sau khi ghi rõ không thuộc roadmap hiện tại.

### 6.4. Ma trận source-code review bắt buộc

Đây là review trực tiếp implementation, không chỉ chạy test hoặc thao tác UI. Mỗi finding phải trỏ tới file/dòng/commit và caller/callee liên quan.

Tạo `Source Coverage Ledger` liệt kê **mọi production source/config/migration file** với module, trách nhiệm, risk tier, reviewer, trạng thái và evidence. Các file mutation tài chính, auth/authorization, reservation/availability, upload, scheduler, migration và deployment phải được đọc thủ công đầy đủ; file low-risk vẫn phải qua static analysis và được đánh dấu review/N/A, không được biến mất khỏi inventory.

#### Backend Java/Spring Boot

| Vùng code | Nội dung phải review |
| --- | --- |
| `controller` | Route/method/status; `@PreAuthorize`; ownership; validation; idempotency header; DTO/error contract; không chứa business logic hoặc gọi repository trực tiếp |
| DTO/request/response/mapper | Nullability, validation groups, enum/time/money serialization, không expose entity/secret/PII ngoài contract, backward compatibility |
| `service`/orchestrator | SRP, transaction boundary, thứ tự side effect, rollback, retry, audit/outbox, race condition, temporal coupling và duplicated rule |
| payment/refund/ledger/SePay | Amount/source/status canonical, provider timestamp, HMAC/auth, dedup, atomicity, matching, reconciliation và không còn nhánh VNPay runtime |
| reservation/RoomHold/pricing | State transition, quote/snapshot, capacity, locking, multi-room allocation, actual/expected price và boundary từng phút |
| guest/check-in/checkout | Validation/capacity, assignment, reconciliation revalidation, invoice snapshot, one-time transition và room release |
| add-on/workforce/cashier/reporting | State machine, actor/session attribution, timezone grouping, formula/source of truth và role split |
| `repository`/query | Parameter binding, SQL/JPQL injection, N+1, unbounded query, fetch graph, pagination, locking, deterministic ordering và query plan |
| `entity` | Mapping/schema parity, precision/scale, `Instant` vs local stay time, lazy/eager, cascade/orphan, equals/hashCode và optimistic version |
| scheduler/async/outbox | Duplicate execution, distributed/single-instance assumption, retry/backoff, poison record, shutdown/restart và observability |
| config/security/filter | Profile drift, cookie/CORS/CSRF/headers, auth filter order, forwarded headers, rate limit, secret/env default và production-safe fail-fast |
| exception/log/audit | Error mapping ổn định, correlation ID, không leak stack/PII/secret, audit completeness/immutability |
| tests/fixtures/seeds | Assertion chất lượng, false-positive mock, transaction isolation, deterministic clock, test-data cleanup, seed idempotency và không hardcode mapping dễ vỡ |

Review từng method có mutation nhạy cảm bằng call graph đầy đủ từ HTTP/controller đến commit DB/provider call; xác định rõ thao tác nào nằm trong transaction và thao tác nào phải qua outbox/reconciliation.

#### Frontend Next.js/React/TypeScript

| Vùng code | Nội dung phải review |
| --- | --- |
| `app/**/page.tsx`, layouts, templates, route handlers | Server/client boundary, route guard, metadata, redirect, deep-link/refresh, caching/dynamic rendering và locale |
| API client/proxy | Base URL, same-origin credentials, timeout/abort, retry, error normalization, idempotency key, no secret exposure và response typing |
| auth/context/hooks | Hydration/session refresh, stale token, race/logout, role checks, effect dependencies, listener/timer cleanup và single-device UX |
| reservation/payment/refund components | State machine parity, stale quote/result, double submit, amount formatting, QR lifecycle, polling cleanup và safe recovery |
| dashboard tables/filters/modals | Server-side pagination khi cần, stable keys, filter reset, detail identity, focus/scroll/nested modal, permission-aware action rendering |
| forms/validation | Client/server rule parity, inline field errors, invalid date/time/capacity, preserved input, accessibility và sanitization |
| media/gallery | `next/image` sizing/priority, URL allowlist, fallback, memory/bandwidth, no flicker/CLS và responsive source |
| localization/SEO | Không hardcode sai locale, message-key parity, date/currency/timezone, metadata/canonical/hreflang/schema |
| security | XSS/unsafe HTML, URL/open redirect, DOM injection, local/session storage sensitivity, clickjacking assumptions và PII logging |
| performance | Duplicate fetch, request waterfall, excessive client component, render loop, large bundle, memoization đúng chỗ, event/timer leak |
| tests | Unit/component assertions có ý nghĩa, DOM/a11y, mocked API parity, Playwright isolation, screenshot/trace và flaky-test diagnosis |

#### PostgreSQL/Flyway/data access

- Đọc và review từng migration `V1` đến latest, không chỉ chạy Flyway.
- So sánh DDL/entity/repository với `information_schema`, `pg_constraint`, `pg_indexes`, triggers và functions thực tế.
- Trace từng trường tiền/thời gian/status/foreign key từ request → entity → table → report/export.
- Kiểm tra seed/master/demo data theo foreign-key order, stable business code, media references và idempotency.
- Kiểm tra SQL native/JPQL/dynamic filtering để loại trừ injection, type coercion, timezone/rounding sai và full-table scan.
- Chạy invariant queries phát hiện orphan, duplicate active hold/session, overlap room/rate, ledger/refund mismatch và snapshot drift.

#### Đối chiếu xuyên lớp

- REST/OpenAPI ↔ TypeScript caller/type ↔ UI rendering.
- Enum/status/error code ↔ badge/action/filter ở frontend ↔ DB constraint.
- Role/ownership ở UI ↔ backend authorization ↔ query scoping.
- Amount/time/locale/nullability ↔ JSON serialization ↔ PostgreSQL precision/timezone.
- Mỗi workflow quan trọng phải có một trace sheet chỉ ra file/method/query/test tương ứng.

Không bulk-refactor trong lúc review. Nếu thấy debt nhưng chưa tái hiện lỗi, ghi finding/đề xuất riêng; chỉ thay code khi đã xác định blast radius và có regression bảo vệ workflow hiện tại.

## 7. Ma trận nghiệp vụ và workflow

Mỗi workflow dưới đây phải kiểm tra: happy path, invalid input, authorization/ownership, boundary time, retry, concurrency, rollback, audit, notification, DB/ledger effects, UI states và recovery sau downtime.

### 7.1. Identity và account

- Đăng ký thường: username/email không phân biệt hoa thường, unique, password policy, consent.
- Xác thực email: gửi, resend, expiry, one-time use, redirect đúng trang, template và failure states.
- Login bằng username hoặc email; lỗi sai credential không rò rỉ account existence.
- Access/refresh token, cookie `Secure`/`HttpOnly`/`SameSite=Lax` cho same-origin proxy, refresh rotation, logout và revoke.
- ADMIN/STAFF single-device rule và CUSTOMER session behavior.
- Forgot/reset password, expiry/reuse, revoke session cũ theo contract.
- Google/Facebook: user mới, user cũ, provider subject mapping, trùng email, Facebook thiếu email phải complete profile, cancel/error callback.
- Profile, password, accessibility settings, delete-data request và ownership.

### 7.2. Catalog, media và public discovery

- CRUD Room, RoomType, Facility/Tiện nghi, Gallery, Add-on Service.
- Active/inactive service không hiển thị cho khách nhưng history/snapshot không mất.
- RoomType tối đa/đủ ba ảnh; Facility/add-on multiple image order; không còn mapping hardcode.
- Upload MIME/content/size/dimension/polyglot, authorization, Cloudinary failure, orphan cleanup và fallback ảnh.
- Room/facility detail modal, favorite, review, gallery swipe/navigation, image loading/CLS.
- Search/filter/sort/pagination/empty state; catalog dữ liệu lớn và khả năng chuyển sang server-side search.

### 7.3. Availability, pricing và booking

- Arbitrary minute check-in/out; checkout phải sau check-in; URL time invalid phải báo lỗi rõ và xóa stale result.
- Số người reservation, từng room type, từng physical room và guest record không vượt capacity; vẫn linh hoạt với khách đại diện.
- Một đơn nhiều loại và số lượng phòng; price per line và tổng đơn đúng.
- Pricing V2: hourly/overnight/daily, late arrival, early checkout không hạ gói trái policy, late hour, 20-hour/day boundaries, multi-day remainder và monotonicity theo từng phút.
- Rate version/effective time/overlap guard; quote TTL; feature-flag/canary; timezone/DST assumptions.
- Giá hiển thị public (qua đêm), room detail (qua đêm/ngày), booking dynamic theo stay window; không dùng legacy hourly catalog field.
- Add-on booking-time theo quantity/unit/package cycle và deposit base; note/validation/snapshot.
- Terms: nút vẫn bấm được để hiện đúng thông báo “Bạn phải đồng ý với Điều khoản & Điều kiện trước khi đặt phòng”; submit không tạo side effect nếu chưa đồng ý.
- Guest/authenticated booking, ownership token, draft/confirm contract, duplicate click/retry.

### 7.4. RoomHold, SePay và ledger

- Không tạo hold trước deposit QR; tạo đúng allocation khi tạo QR; TTL và purpose.
- 50%/100%, QR expiration/abandon, refresh page, retry idempotent.
- Webhook Authorization/HMAC/API-key theo cấu hình hiện hành, merchant account, timestamp tolerance, replay và rate limit.
- Dedup order: provider event ID, merchant+transaction, deterministic fingerprint theo canonical contract.
- Exact payment, underpayment, overpayment, two transfers, duplicate webhook, late payment, unmatched transfer.
- Downtime/reconciliation pagination/lookback; không bỏ sót provider event; reconciliation cũng idempotent.
- Transaction/event/ledger/refund obligation atomic; injected failure rollback.
- Incoming và outgoing phải phân loại đúng; không còn VNPay runtime/DB artifacts được dùng.

### 7.5. Reservation operations

- Staff confirm/reject, reason, audit và room availability effect.
- Room assignment đúng room type/số lượng; optimistic/pessimistic lock; two-staff race.
- Check-in: representative guest, all guest validation, document/phone constraints, actual time, one-time transition.
- Walk-in CASH/SEPAY/UNPAID: guest, room, price, add-on, payment/cashier shift và atomicity.
- No-show: status/time/actualCheckIn null; cancellation/refund obligation.
- In-stay add-on REQUESTED/CONFIRMED/FULFILLED/CANCELLED; inventory/price snapshot; obligation update.
- Additional fee, extra guest fee, expected vs actual room charge, early/late checkout.
- Final payment CASH/QR; idempotency và ledger attribution.

### 7.6. Refund và cancellation

- Customer cancellation phải cung cấp bank name/account/account holder cho bank refund.
- Cancellation penalty manual amount/policy snapshot; refund amount exact và không âm/vượt obligation.
- QR/bank: create `PENDING`, unique refund code, exact amount/content match, SePay `out`, unmatched outgoing queue.
- Manual fallback chỉ theo delay/role/reason/proof policy; không bypass ledger.
- Cash: `PENDING` → explicit handover confirmation → `COMPLETED`, không yêu cầu proof.
- Cancelled refund không xóa nghĩa vụ; retry/concurrency không double-refund.
- Đóng modal giữa chừng giữ nguyên reservation; chỉ completion trigger final status.

### 7.7. Checkout, invoice và review

- Preview “Đã thu/Cần thu” dùng current facts; checkout revalidate trong transaction.
- Phân biệt thiếu tiền, pending service/refund, sai fee/data và technical exception; đưa đúng action xử lý.
- STAFF không force checkout; exception queue chỉ dành ADMIN và không cho nhập/xóa số tiền tùy ý.
- Pending request tự resolve khi payment/refund/fee correction làm khớp.
- Double checkout/concurrent payment race; physical room release chỉ sau checkout thành công.
- Immutable invoice snapshot, print/download, multi-room breakdown, add-on/fee/refund/payment details.
- Review nhiều room type trong một reservation; ownership, one-per-target policy và moderation.

### 7.8. Workforce và cashier shifts

- Shift template, assignment, self-request, ADMIN approval/rejection, empty slots và role visibility.
- Calendar day/week/month, STAFF chỉ thấy lịch/yêu cầu của mình nhưng thấy capacity trống/đủ; ADMIN thấy assignees/details.
- WorkShiftSession check-in window, early/late/absent/missed checkout/auto-close policy.
- Một STAFF tối đa một WorkShiftSession `ACTIVE` và một CashierShift `OPEN`.
- Check-in work shift mở cashier shift nguyên tử; checkout đóng cashier shift nguyên tử; rollback cả hai nếu một bước lỗi.
- ADMIN xử lý reservation không bị buộc mở cashier shift; STAFF cash mutation phải được gắn đúng session.
- Attendance statistics theo nhân viên/ngày/tuần/tháng; late/absent/overtime và drill-down chính xác.

### 7.9. Reporting và accounting operations

- Tách rõ cash flow và revenue recognition:
  - Thu tiền mặt/chuyển khoản = successful money-in được gắn đơn.
  - Hoàn tiền mặt/chuyển khoản = completed money-out/refund.
  - Thực nhận = tổng thu trừ tổng hoàn trong kỳ.
  - Doanh thu lưu trú chỉ được gọi là doanh thu nếu đã có policy ghi nhận rõ (không đồng nhất mù quáng với tiền cọc).
- Deposit, final payment, fee dự kiến/thực tế, add-on và refund không bị cộng hai lần.
- Group day/week/month sau khi convert UTC → `Asia/Ho_Chi_Minh` rồi mới truncate.
- Chi tiết theo reservation phải reconcile với payment/refund ledger và invoice; modal không trộn nghĩa vụ với dòng tiền.
- Cashier shift chỉ ghi nhận giao dịch thuộc thời gian/actor của ca; không yêu cầu opening balance theo thiết kế hiện tại.
- Business-day close/journal cũ: xác minh mục đích còn cần thiết; ẩn/đơn giản hóa UI không được xóa integrity control nếu vẫn là source of truth.
- Export CSV/XLSX/PDF/print: filter, totals, encoding tiếng Việt, access control và công thức.

### 7.10. Support, audit, monitoring và system jobs

- Contact/support request, reply email, ownership, spam/rate limit và status.
- Chatbot deterministic answers, free-text provider timeout/error, no silent failure, no N+1 room/review calls.
- Audit operational/management actions; actor/role/entity/correlation/old-new/detail; high-risk badge/alert.
- Không log login noise/invoice print theo policy; không log raw secrets/PII; append-only DB guard.
- Scheduler: hold expiry, reservation cleanup, SePay reconciliation, outbox retry, refund fallback, forgotten work shift.
- Mỗi job phải single-effect khi chạy trùng/restart; có metric, log, alert và manual recovery.

## 8. Review backend/API/integration

### 8.1. Code và kiến trúc

- Re-run SOLID/package audit thay vì mặc định `SOLID_AUDIT.md` còn đúng.
- Kiểm tra SRP/OCP/LSP/ISP/DIP, constructor injection, transaction placement và domain boundaries.
- Dùng ArchUnit hoặc equivalent để chặn controller → repository, package cycle và forbidden dependency sau khi baseline được duyệt.
- Tìm god service, duplicated business rule, temporal coupling, hidden side effect, mutable singleton và thread-unsafe cache.
- Kiểm tra exception taxonomy, consistent error envelope, correlation ID và không rò stack trace.

### 8.2. REST contract

- Inventory toàn bộ endpoint bằng OpenAPI + source scan; đối chiếu caller frontend.
- Method/status/content-type/validation/error code thống nhất.
- DTO field backward compatibility; nullability; enum legacy/canonical mapping.
- Authorization tại backend cho từng endpoint; không dựa vào ẩn nút frontend.
- Ownership/IDOR cho reservation, invoice, refund, review, profile, media và work schedule.
- Pagination, maximum page size, filter allowlist, sort allowlist; không tải toàn bộ dữ liệu lớn.
- Idempotency key scope, request hash, replay response, TTL, conflict và concurrent duplicate.
- API versioning/deprecation policy được ghi thành ADR; không đổi route chỉ để “đẹp”.

### 8.3. Integration controls

- Mọi network call có connect/read/overall timeout, retry có backoff+jitter, retry budget và circuit/fallback phù hợp.
- Không retry non-idempotent request nếu chưa có key/dedup.
- Webhook signature/auth, replay window, payload schema/version, poison event và dead-letter/review queue.
- SendGrid template ID thuộc đúng account, dynamic data đầy đủ, fallback HTML an toàn và outbox retry.
- OAuth redirect URI cho local/preview/production; state/nonce/ticket one-time/expiry.
- Cloudinary signed upload/delete, URL transformation, quota và orphan reconciliation.

## 9. Review database, migration và data governance

### 9.1. Schema và integrity

- ERD thực tế từ PostgreSQL so với entity/repository/docs.
- PK/FK/unique/check/not-null/default/cascade; enum/check constraints và orphan detection.
- Financial timestamps dùng `Instant/UTC`; stay/local business time có semantics được ghi rõ.
- Money dùng numeric precision/scale, rounding và currency; không dùng float/double.
- Version columns/locks cho write contention; tránh lost update và write skew.
- Append-only audit/ledger/invoice trigger hoặc privilege guard.

### 9.2. Query và hiệu năng

- Thu query count trên các trang list/detail lớn; phát hiện N+1 bằng SQL statistics/profiler.
- `EXPLAIN (ANALYZE, BUFFERS)` cho availability, dashboard, reports, audit, work calendar, provider matching.
- Index selectivity/order/covering/partial; unused/duplicate index; bloat/vacuum/analyze.
- Pagination ổn định, tránh unbounded join/fetch và Cartesian explosion.
- Connection pooling (Hikari/Neon pooler), max lifetime, timeout, pool exhaustion và leak detection.
- Deadlock/concurrency matrix cho room assignment, hold, payment, refund, checkout và shift.

### 9.3. Flyway

- Fresh PostgreSQL 16: apply `V1` → latest và Hibernate `validate`.
- Existing-compatible DB có dữ liệu cũ: preflight, checksum, nullable backfill, constraint validation và post-validate.
- Migration transactionality, lock time, data volume estimate, forward-fix và rollback/restore runbook.
- Không edit migration đã áp dụng; checksum mismatch phải điều tra và có evidence.
- Seed master/demo tách biệt; production seed idempotent, không tạo demo user/scenario ngoài cờ rõ ràng.

### 9.4. Data governance

- Data dictionary: field, owner, source, sensitivity, retention, deletion, lineage.
- PII inventory: user, guest, document, phone, email, bank recipient, audit detail.
- Encryption at rest/in transit; application encryption key rotation cho dữ liệu refund.
- Masking log/export/non-prod clone; test data không lấy PII production chưa ẩn danh.
- User data export/delete; legal retention exception cho invoice/financial/audit.
- Backup schedule/retention/region/ownership và restore verification.

## 10. Review frontend, UX, accessibility và web quality

### 10.1. Functional UI

- Tất cả route public/auth/dashboard, deep link, refresh, back/forward và unauthorized redirect.
- Mọi button/link/action có default/hover/active/focus-visible/disabled/loading và feedback.
- Form có label, inline validation cụ thể, giữ dữ liệu khi lỗi, server error mapping và no double-submit.
- Modal/dropdown/tooltip: centered, viewport-bound, focus trap, Esc, click-outside, nested-modal policy và scroll lock.
- Loading skeleton đúng aspect ratio; empty/error/retry/offline states; không layout shift.
- Filter có clear/reset, URL/state persistence hợp lý và không bắt nhập dữ liệu hệ thống có thể tự điền.
- Print/export/invoice và browser popup handling.

### 10.2. Responsive và browser matrix

Tối thiểu:

| Nhóm | Kích thước/thiết bị |
| --- | --- |
| Mobile nhỏ | 320×568 |
| Mobile phổ biến | 360×800, 390×844, Pixel 7 |
| Tablet | 768×1024, 820×1180 |
| Laptop | 1366×768 |
| Desktop | 1440×900, 1920×1080 |
| Zoom | 80%, 100%, 125%, 200%, 400% cho nội dung chính |

Browser: Chrome, Edge, Firefox; Safari/iOS bằng thiết bị thật hoặc cloud-browser trước go-live. Kiểm tra notch/safe-area, 44px tap target, swipe gallery, no horizontal overflow, on-screen keyboard và orientation.

### 10.3. Accessibility

- WCAG 2.2 AA: contrast, keyboard-only, visible focus, headings/landmarks, label/name/role/value.
- ARIA cho icon-only/control động; live region cho toast/error/payment state.
- Modal focus restore, skip link, logical tab order, table headers/caption.
- `prefers-reduced-motion`, font scaling, color-independent status.
- Automated axe + manual keyboard + NVDA screen-reader flows CUSTOMER/STAFF/ADMIN.

### 10.4. Localization, time và formatting

- Toàn bộ vi/en strings; không lẫn tiếng Việt khi EN hoặc hardcode ngoài catalog có chủ đích.
- Date/time/currency/number/plural; UTC/provider time/business time; locale switch persistence.
- Email, error, invoice, export và metadata theo locale.
- Missing translation fail-safe và key scan trong CI.

### 10.5. SEO và web standards

- Unique title/description, canonical, robots, sitemap, OpenGraph/Twitter cards.
- Locale-aware canonical/hreflang chỉ khi có server-routable locale; không khai báo giả.
- Structured data phù hợp (Hotel/Organization/Breadcrumb), schema validation.
- Semantic HTML, crawlable links, 404/500 pages, favicon/manifest.
- Core Web Vitals, caching, image `sizes`/priority, font strategy và bundle analysis.

### 10.6. Analytics và product metrics

- Event dictionary có version/owner cho funnel: xem phòng → search → chọn room type → booking form → tạo QR → payment → confirmed → check-in → checkout → review.
- Phân biệt conversion/drop-off kỹ thuật với số liệu tài chính; không dùng analytics làm source of truth cho tiền.
- Event không chứa raw email/phone/document/bank info/token; consent và retention theo privacy policy.
- Tránh double-event do React rerender/retry/navigation; event ID/session attribution và bot/internal-traffic filtering.
- Dashboard KPI tối thiểu: availability/search success, quote-to-booking, QR-to-payment, cancellation/no-show, add-on attach rate, checkout mismatch và support failure.
- Xác minh analytics disabled/fallback không làm hỏng workflow. A/B testing mặc định `N/A` nếu chưa có governance, sample size và consent.

## 11. Security và privacy review

### 11.1. Threat model

Lập asset/trust-boundary/attacker/abuse-case cho:

- credentials, tokens, refresh cookies và OAuth tickets;
- reservation ownership, room availability và staff/admin authorization;
- payment/refund/ledger/webhook/reconciliation;
- uploads/Cloudinary, chatbot/provider calls, email templates;
- audit/log/export/backup và production administration.

### 11.2. Security controls/test

- OWASP ASVS/Top 10: broken access control, crypto failures, injection, insecure design, misconfiguration, vulnerable components, auth failure, integrity, logging/SSRF.
- SQL injection/HQL injection, XSS stored/reflected/DOM, CSRF, CORS, CSP, clickjacking, MIME sniffing, open redirect.
- IDOR/mass assignment/property injection, path traversal, SSRF, file polyglot/zip bomb, malicious SVG/image.
- Brute force/credential stuffing, username/email enumeration, token fixation/replay, OAuth account-link confusion.
- Webhook forgery/replay, idempotency abuse, amount/content manipulation và timing boundary.
- Header/cookie/TLS/HSTS policy; proxy forwarded-header trust.
- Secret scanning toàn Git history và artifacts; least-privilege GitHub/Vercel/Render/Neon/Cloudinary/SendGrid/SePay keys; rotation drill.
- SAST/SCA/container/IaC scan, Maven/Node dependency review, SBOM và license matrix.
- DAST chỉ trên approved staging. Penetration test độc lập trước khi xử lý dữ liệu/tiền thật ở quy mô production.

### 11.3. Privacy/legal

- Consent, privacy/terms/cancellation/booking policies và version acceptance.
- Purpose limitation, retention, access/export/delete request và incident notification.
- Email opt-in/transactional classification; unsubscribe nơi áp dụng.
- Review pháp lý Việt Nam cho PII, hóa đơn/kế toán và chính sách lưu dữ liệu; kỹ thuật không tự tuyên bố compliance pháp lý.

## 12. Test strategy theo wave

### Wave 0 — Baseline, scope và evidence

1. Khóa Review Manifest và working tree.
2. Chụp inventory source/config/cloud ở chế độ read-only.
3. Xác minh test accounts/data, environment topology và safety boundaries.
4. Tạo gap register ban đầu; không fix trước khi baseline hoàn thành.

**Exit:** ref/môi trường/data/artifact rõ ràng; không còn ambiguity về checkout đang review.

### Wave 1 — Static, architecture và dependency

- Backend compile, unit; frontend lint/typecheck/unit/build.
- ArchUnit/package-cycle analysis, duplicate/dead code, TODO/FIXME, forbidden VNPay/MySQL runtime scan.
- Secret scan, dependency/CVE/license/SBOM và Dockerfile/Compose/IaC lint.
- API/route/config/env inventory và documentation drift.

**Exit:** không có compile/static P0/P1; dependency/security findings được phân loại.

### Wave 2 — Database/Flyway/integrity

- Fresh PostgreSQL 16 Testcontainers.
- Existing-data compatible clone; preflight/backfill/constraint/postflight.
- Schema/entity validation, seeds, invariant SQL, timestamp/money checks.
- Query plans/N+1/index/pool/deadlock/concurrency baseline.

**Exit:** fresh và upgrade path pass; không mất dữ liệu; restore point có thể dùng.

### Wave 3 — Backend unit/integration/contract

- Unit/property/boundary tests cho pricing, money, time, capacity, status transition.
- MockMvc/API contract, role/ownership/error envelope/pagination/idempotency.
- Testcontainers atomicity/concurrency; provider adapters bằng fake server.
- Scheduler/retry/outbox/reconciliation downtime recovery.

**Exit:** workflow critical có automation ở service + API + PostgreSQL level.

### Wave 4 — Frontend component/E2E/accessibility

- Component state/validation/modal/table/filter/locale tests.
- Playwright desktop/mobile cho public/auth/dashboard; API assertions đi kèm UI.
- axe, keyboard, NVDA scripts; visual/responsive/overflow and image-loading checks.
- Throttled network, backend timeout, 4xx/5xx, stale session và retry UI.

**Exit:** mọi route trọng yếu có success + failure + permission path; không blocker mobile/a11y.

### Wave 5 — End-to-end business regression

Chạy tuần tự toàn bộ mục 7 trên local/staging với DB query xác minh sau mỗi side effect. Bao gồm multi-room, pricing boundaries, RoomHold, SePay fake events, refund, checkout, shifts, reporting và audit.

**Exit:** không có P0/P1; ledger/reservation/invoice/report reconcile bằng SQL độc lập.

### Wave 6 — Performance/capacity

Chỉ chạy trên staging clone:

- smoke: 5 virtual users/5 phút;
- normal load đề xuất: 20 VU/30 phút;
- stress: tăng bậc đến khi vi phạm SLO, tối đa ban đầu 50 VU;
- soak: 10 VU/2 giờ;
- spike: public browse/search burst và webhook retry burst;
- mixed scenario: catalog/search 45%, booking quote 20%, dashboard list 15%, mutation giả lập 10%, report 5%, webhook/reconciliation 5%.

Không dùng real bank provider trong load test. Với mutation tài chính, fake provider + unique idempotency key + disposable data.

SLO ban đầu để đo (sẽ chốt với owner sau baseline):

- error rate < 1% ngoài lỗi validation chủ động;
- warm public/API read p95 ≤ 750 ms; write nội bộ p95 ≤ 1.5 s, không tính external-provider wait;
- không lost/duplicate financial effect ở bất kỳ tải nào;
- DB pool không cạn kéo dài, không deadlock/livelock, scheduler không backlog vô hạn;
- p75 LCP ≤ 2.5 s trên trang public warm-cache/mobile mid-tier; CLS ≤ 0.1; INP ≤ 200 ms;
- ghi riêng cold-start Render Free, không trộn với warm SLO.

**Exit:** capacity envelope và bottleneck có số liệu; không dùng “cảm thấy nhanh”.

### Wave 7 — Resilience/chaos và recovery

Trên môi trường disposable/staging:

- DB latency/connection refusal/pool exhaustion;
- SePay/SendGrid/Cloudinary/OAuth/Gemini timeout, 429, 500, malformed response;
- webhook retry storm/out-of-order/duplicate;
- backend restart giữa payment/refund/reconciliation/outbox;
- scheduler chạy trùng; clock skew trong tolerance;
- Render cold start, Vercel proxy timeout, Neon maintenance/disconnect;
- partial transaction failure và rollback;
- backup restore, point-in-time/branch restore, app validation và rollback rehearsal.

**Exit:** RPO/RTO đo được, recovery runbook chạy được, alert thực sự đến người chịu trách nhiệm.

### Wave 8 — Security validation

- Automated SAST/SCA/secret/container/DAST trên staging.
- Manual authorization/ownership/abuse tests theo role.
- Upload/chatbot/webhook/OAuth/payment/refund threat scenarios.
- Verify remediation; independent pen test nếu go-live tiền thật/PII production.

**Exit:** 0 open Critical/High; Medium có owner/risk acceptance/deadline.

### Wave 9 — Operator UAT

UAT bằng CUSTOMER/STAFF/ADMIN thật trên staging gần production; production chỉ dùng controlled low-risk cases. Mỗi case có tester, timestamp, screenshot/video, IDs, expected/actual và sign-off.

**Exit:** operator ký xác nhận; mọi deviation vào bug register.

### Wave 10 — Deployment, production smoke và monitoring

- Enforce GitHub ruleset và prove red check blocks merge.
- Vercel deployment checks, Render `checksPass`, migration sequencing và env diff.
- Deploy canary/feature flag; health/readiness/smoke.
- Verify logs/metrics/alerts/Sentry/UptimeRobot/SendGrid receipt.
- Reconcile database/ledger after controlled production scenario.
- Rollback rehearsal hoặc documented forward-fix decision.

**Exit:** Go/No-Go board ký quyết định và post-deploy observation window hoàn tất.

## 13. Bộ UAT bắt buộc

### 13.1. CUSTOMER

1. Đăng ký thường → nhận email → xác thực → login bằng username và email → logout/login lại.
2. Google user mới/user cũ; Facebook có email/thiếu email; callback failure và cancel.
3. Search ngày/giờ hợp lệ, checkout trước check-in, URL invalid, số khách/capacity.
4. Chọn nhiều room type/số lượng; xem giá từng dòng/tổng; add-on booking-time.
5. Đặt cọc 50% và 100%; retry click; QR timeout/abandon; refresh/resume.
6. My Bookings với nhiều đơn; detail, invoice, contact, cancellation và recipient bank info.
7. Nhận refund progress; review nhiều loại phòng sau checkout; favorite/profile/accessibility/locale.

### 13.2. STAFF

1. Xem lịch, đăng ký ca trống, được duyệt; check-in ca → cashier mở tự động.
2. Confirm/reject reservation; xem đủ room lines; assign đúng phòng và check-in guest.
3. Walk-in CASH/SEPAY/UNPAID; validation sai phải báo cụ thể và không tạo dữ liệu dở dang.
4. Add in-stay service, extra guest/fee; cash final payment; reconciliation preview.
5. Checkout khớp; mismatch không có force path; cash refund explicit handover.
6. Checkout ca → cashier đóng; late/missed checkout handling; xem thống kê của mình.
7. Xác minh STAFF không thấy audit/admin finance/user management và không gọi API được.

### 13.3. ADMIN

1. CRUD catalog/media/add-on, active/inactive, rooms/users/roles.
2. Phân ca/approve/reject/edit; calendar day/week/month; attendance drill-down.
3. Xử lý unmatched payment/outgoing/refund review; không bypass ledger.
4. Bank refund chờ SePay outgoing; fallback theo policy; cancellation penalty snapshot.
5. Financial report day/week/month, cash/transfer received/refunded, detail per reservation và export.
6. Audit filter/detail/high-risk alert; append-only và actor/correlation correctness.
7. Xử lý reconciliation exception hợp lệ; không nhập số tùy ý/force checkout.

### 13.4. SYSTEM/provider

1. Hold expiry/cleanup/no-show/reconciliation/outbox jobs chạy đúng, không lặp effect.
2. Một incoming thật giá trị thấp: exact hoặc deposit; xác minh SePay → event → payment → ledger → reservation/hold.
3. Một outgoing/refund thật: customer cung cấp account; xác minh SePay `out` → refund → ledger → reservation.
4. Under/over/duplicate/late/final-payment còn lại chạy bằng fake provider hoặc cash theo contract, không phát sinh nhiều giao dịch thật.
5. SendGrid inbox/spam/template/dynamic link; OAuth production callback; Cloudinary upload/delete/fallback.

## 14. Performance, scalability và cost review

- Route/API/bundle/image waterfall; server/client component và repeated fetch.
- Cache policy cho catalog/media; invalidation khi CRUD; không cache private data.
- Next image sizes/format/priority, Cloudinary transforms, CDN hit ratio và payload budget.
- Backend serialization/query count, report aggregation, scheduler contention, JVM heap/GC.
- Hikari/Neon pooler sizing cho Render memory limit; slow query và connection churn.
- Rate limit/caching/state khi scale >1 instance.
- Render Free cold start và uptime ping: đo benefit/cost, không tuyên bố SLA giả.
- Vercel/Render/Neon/Cloudinary/SendGrid/SePay quotas, alert threshold, monthly cost envelope và vendor lock-in/exit plan.
- Capacity forecast: rooms, reservations/day, concurrent staff/customers, media volume, audit/ledger growth và retention.

## 15. Infrastructure và deployment checklist

### 15.1. GitHub

- Branch flow feature/fix → develop → main; hotfix back-merge.
- Ruleset bắt buộc `Branch policy`, `Backend and PostgreSQL 16`, `Frontend`; up-to-date branch; no direct push.
- Prove check đỏ chặn merge; required review/owner policy phù hợp dự án một người.
- Least-privilege Actions permissions, pinned actions, Dependabot, secret scan, artifact retention.
- Release tag/changelog/SBOM/provenance và rollback ref.

### 15.2. Vercel

- Production/Preview env separation; no secret exposed as `NEXT_PUBLIC_*`.
- Correct Git branch, deployment checks, immutable commit mapping.
- Same-origin proxy timeout/error/cookie/OAuth behavior.
- Cache/image/security headers, domain/TLS/redirect, Web Vitals và rollback.

### 15.3. Render

- Region Singapore/Vietnam latency, free-plan limits/cold start/OOM.
- `checksPass`, health/readiness, graceful shutdown, JVM/container memory và DB pool.
- Env inventory/ownership/rotation; Pricing V2 flags; seeds disabled; forwarded headers/CORS/cookies.
- Deploy/migration order, logs, metrics, restart behavior và rollback.

### 15.4. Neon

- PostgreSQL version/region/branch, direct vs pooled URL, SSL và connection limit.
- Migration lock, query insights, slow queries, storage/compute quota.
- Current backup/PITR/retention; create restore clone, validate app/SQL, measure RPO/RTO.
- Production role least privilege; separate migration/runtime user nếu khả thi.

### 15.5. Docker/local

- `docker compose config --quiet`, health checks, dependency order, log rotation, graceful stop.
- Fresh volume migration test và preserved legacy volume `backend_postgres-data` ownership.
- Images non-root/least privilege, no secret baked into layers, multi-stage size, Trivy scan.
- Reproducible local setup; backend/frontend chạy native khi QA cần giảm tải máy, PostgreSQL có thể chạy Docker riêng.

### 15.6. Providers

- SePay endpoint/secret/token/account ID/merchant exact; webhook URL TLS; reconciliation quota.
- SendGrid sender/domain authentication SPF/DKIM/DMARC, valid template IDs cùng account, suppression/bounce/spam.
- Google/Facebook app live/test mode, consent/privacy links, redirect URIs và scopes tối thiểu.
- Cloudinary signed credentials, folder/transform/access/delete/quota/retention.
- UptimeRobot/Sentry alert routing, ownership, dedup/noise và receipt drill.

## 16. Observability, audit và incident response

- Structured logs với timestamp UTC, level, service, correlation/request ID, actor ID đã mask, entity/action/outcome/duration.
- Metrics: request latency/error, DB pool/query, scheduler backlog, webhook/reconciliation/outbox, payment/refund unmatched, hold expiry, checkout mismatch, email failures.
- Business safety alerts: duplicate/replay, unmatched money, refund overdue, ledger mismatch, high-risk ADMIN action, job failure.
- Dashboard tách health kỹ thuật và business operations; STAFF/ADMIN access đúng.
- Alert threshold, owner, escalation, quiet hours, dedup và test delivery.
- Incident runbooks: payment missing, outgoing unmatched, DB unavailable, auth outage, data leak, deploy regression, provider outage.
- Incident drill có timeline, detection/ack/recovery, RTO/RPO actual và follow-up actions.

## 17. Documentation và developer experience

- README/setup/profile/env/Compose commands đúng current HEAD.
- OpenAPI/API summary và route count regenerate; DTO/error/idempotency documented.
- Architecture overview + ADRs: same-origin cookie, PostgreSQL-only, SePay-only, pricing, snapshot, audit, rate-limit scaling.
- State diagrams: reservation, hold, payment, refund, add-on, work/cashier shift.
- Data dictionary/ERD/migration runbook/backup restore/rollback/cutover.
- Operator manuals CUSTOMER support, STAFF front desk/cashier, ADMIN finance/audit/schedule.
- Test report không để stale counts; historical report ghi rõ ref và không được dùng như current evidence.
- Ownership matrix, on-call contacts, secret rotation calendar, feature flag owner/expiry.

## 18. Automation cần bổ sung sau baseline nếu đang thiếu

Không thêm tool chỉ để tick checklist; trước tiên kiểm tra khả năng tái dùng. Các candidate:

- ArchUnit package boundary/cycle tests.
- JaCoCo backend và Vitest coverage thresholds theo critical modules, không chạy theo tổng % giả tạo.
- Playwright + axe cho accessibility smoke.
- OpenAPI contract/diff gate và frontend API caller compatibility check.
- OWASP Dependency-Check hoặc GitHub dependency review cho Maven; license/SBOM gate.
- Gitleaks/secret scan; Trivy image/config scan; ZAP baseline staging.
- k6/Gatling load profiles và SQL invariant verification sau load.
- Restore-drill workflow/manual runbook có artifact.
- Locale-key parity, dead-link, sitemap/schema/SEO checks.

Mọi tool mới phải pin version, document false-positive policy, runtime/cost và owner.

## 19. Evidence và artifact structure

Mỗi đợt tạo thư mục ngoài source artifact hoặc CI artifact theo mẫu:

```text
qa-artifacts/<YYYYMMDD-HHMM>-<short-sha>/
  00-manifest/
  01-static-architecture/
  02-backend/
  03-frontend/
  04-database-migration/
  05-api-contract/
  06-e2e-uat/
  07-security/
  08-performance-resilience/
  09-cloud-dr/
  10-release/
```

Mỗi command ghi command, working directory, start/end, exit code, tool version và log. Screenshot/video phải đặt tên theo case ID; SQL evidence phải mask PII.

### Finding template

```markdown
ID: QA-<AREA>-NNN
Severity: P0/P1/P2/P3
Status: OPEN/IN_PROGRESS/READY_TO_VERIFY/CLOSED/RISK_ACCEPTED
Ref + environment:
Requirement/invariant:
Preconditions:
Steps to reproduce:
Expected:
Actual:
Evidence:
Impact and blast radius:
Root cause:
Fix/changed files:
Focused regression:
Full regression:
Migration/rollback:
Owner/deadline:
```

### UAT template

```markdown
Case ID / actor / tester:
Ref / deployment / environment:
Test data IDs:
Preconditions:
Steps:
Expected business result:
Expected DB/ledger/audit result:
Actual:
Evidence:
PASS/FAIL/BLOCKED:
Tester sign-off / operator sign-off:
```

## 20. Lệnh baseline dự kiến

Chạy trong PowerShell, từ đúng working directory; điều chỉnh chỉ sau khi đọc profile hiện tại.

```powershell
# Repository identity
git status --short
git branch --show-current
git rev-parse HEAD
git log -1 --format="%h %cI %s"
git diff --check

# Compose model; không xóa volume
docker compose config --quiet
docker compose ps

# Backend fast and full PostgreSQL/Flyway gate
Set-Location code/backend
.\mvnw.cmd -B -ntp test
.\mvnw.cmd -B -ntp -Ppostgres-migration-test verify

# Frontend deterministic gates
Set-Location ..\frontend
pnpm install --frozen-lockfile
pnpm run lint
pnpm run typecheck
pnpm run test:unit
pnpm run build:clean
pnpm audit --prod --audit-level=high

# E2E chỉ sau khi backend/frontend/test data healthy
$env:E2E_BASE_URL='http://127.0.0.1:3000'
pnpm run test:e2e
```

Không chạy `docker compose down -v`, reset database, seed production, ZAP active scan, k6 stress hay real payment bằng một command chung.

## 21. Thứ tự thực thi và điểm review

| Phase | Nội dung | Điểm dừng bắt buộc |
| --- | --- | --- |
| A | Baseline, inventory, architecture, requirement traceability | Duyệt manifest, scope và bất biến |
| B | Static/unit/build/dependency | Duyệt P0/P1 và tooling gaps |
| C | PostgreSQL/Flyway/data/concurrency | Duyệt migration/restore evidence |
| D | Auth/catalog/reservation/pricing/payment/refund/checkout | Duyệt financial invariant report |
| E | Workforce/reporting/audit/system jobs | Duyệt role/operations reconciliation |
| F | Frontend/mobile/a11y/i18n/SEO | Duyệt browser/accessibility report |
| G | Security/privacy | Duyệt threat model và remediation |
| H | Performance/capacity/resilience/DR | Duyệt capacity + RPO/RTO |
| I | UAT/provider/cloud | Operator/provider sign-off |
| J | Full regression/deploy/post-deploy | Go/No-Go board |

P0 làm dừng wave liên quan và chặn release. P1 chặn release nhưng không ngăn tiếp tục review read-only ở vùng độc lập. Bug ngoài phạm vi vẫn ghi nhận, không sửa âm thầm.

## 22. Deliverables cuối cùng

1. Review Manifest và environment/config inventory đã mask secret.
2. Requirement-to-test traceability matrix.
3. Current flow và verified state diagrams.
4. Architecture/dependency/API/role/ownership matrices.
5. Database ERD/data dictionary/migration/restore reports.
6. Gap table `PASS/PARTIAL/FAIL/MISSING/BLOCKED/N/A` với severity/owner.
7. Backend/frontend/API/E2E/UAT test reports và coverage-by-risk.
8. Security/privacy/dependency/license report + SBOM.
9. Performance/capacity/resilience/chaos report.
10. Cloud/config/CI/CD/DR/observability audit.
11. Bug register, fixes, changed files, migrations và regression evidence.
12. Operator UAT sign-off; real SePay/SendGrid/OAuth evidence tách riêng.
13. Rollback/cutover/incident runbooks đã rehearsal.
14. Release recommendation và residual-risk register.

## 23. Definition of Done cho review và cho go-live

### 23.1. Review Complete

- Tất cả mục trong tài liệu có trạng thái và evidence hoặc lý do `N/A/BLOCKED`.
- Tất cả workflow critical có trace từ requirement → automated/manual test → DB/audit evidence.
- Tất cả P0/P1 đã được tái hiện hoặc loại trừ có bằng chứng; không còn vùng “chưa nhìn”.
- Report gắn đúng commit/environment/time; không dùng stale test count.
- Mọi phát hiện có owner, priority, remediation/acceptance và deadline.

### 23.2. Go-Live

- 0 P0/P1 mở; 0 Critical/High security finding mở.
- Backend/frontend/PostgreSQL migration/contract/E2E critical/full regression đều pass trên release ref.
- Fresh DB và existing-data migration pass; current Neon restore drill pass; RPO/RTO được chấp nhận.
- Concurrency/idempotency chứng minh không double-book/charge/refund/checkout và không mất ledger.
- Pricing/RoomHold/payment/refund/check-in/checkout/invoice/reporting reconcile chính xác.
- Sustained load/capacity và provider failure tests đạt SLO đã duyệt.
- GitHub/Vercel/Render release gates thật sự enforce; rollback ref/runbook sẵn sàng.
- Alerts được nhận; audit/log không lộ PII/secret; incident drill hoàn tất.
- CUSTOMER/STAFF/ADMIN operator UAT ký xác nhận.
- Một vòng SePay incoming + outgoing/refund thật và SendGrid/OAuth production smoke có bằng chứng.
- Legal/privacy/license/residual risks được owner chấp nhận bằng văn bản.

Nếu thiếu một cổng bắt buộc, kết luận tối đa là `CONDITIONAL GO` hoặc `NO-GO`; không được đổi nhãn thành “hoàn thiện” vì demo chạy được.

## 24. Ma trận bao phủ chống bỏ sót

Trước khi đóng review, kiểm tra chéo mỗi hàng dưới với ít nhất một test/evidence và một owner:

| Lớp | Bao phủ bắt buộc |
| --- | --- |
| Product/domain | Roles, state machines, pricing, capacity, money, refund, shifts, reports, policies |
| Architecture | Modules, boundaries, cycles, transactions, SPOF, scaling decisions |
| Backend/API | Contract, validation, auth, ownership, idempotency, pagination, integrations, schedulers |
| Frontend | Public/auth/dashboard, states, forms, modals, print/export, responsive, browser |
| Database | Schema, constraints, migration, seed, query/index, lock, backup/restore, PII |
| Security/privacy | Threat model, OWASP, secrets, dependency/license, upload, OAuth/webhook |
| Quality | Unit/integration/contract/E2E/UAT/property/concurrency/regression/coverage |
| Performance | Web Vitals, API/DB, load/stress/soak/spike, pool, capacity, cost |
| Reliability | Retry/timeout/idempotency, chaos, failover, RPO/RTO, incident drill |
| Delivery | Git/CI/CD, environments, flags, migration order, canary, rollback, post-deploy |
| Cloud/providers | GitHub, Vercel, Render, Neon, Docker, SePay, SendGrid, OAuth, Cloudinary, monitoring |
| Operations | Logs, metrics, traces, alerts, audit, support, runbooks, ownership/on-call |
| Web standards | Accessibility, vi/en, timezone, SEO, metadata, analytics, consent |
| Governance | Data dictionary, lineage, retention, deletion, legal, vendor risk, business continuity |

Tài liệu này là checklist gốc. Báo cáo thực thi không được bỏ mục; mục không áp dụng phải ghi `N/A` kèm lý do, không được xóa khỏi phạm vi.
