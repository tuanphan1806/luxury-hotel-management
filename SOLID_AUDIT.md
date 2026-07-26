# SOLID Backend Audit

> Ghi chú cập nhật 24/07/2026: các nhận xét lịch sử về VNPay bên dưới mô tả
> baseline tại thời điểm audit. Runtime VNPay đã được gỡ sau audit; SePay/CASH
> là các provider còn hoạt động.

Ngày rà soát: 2026-07-23
Repository: `C:\Users\admin\Downloads\hotelmanagement-new`
Branch: `main`
Commit: `476f9c656c86af234706acbfff116530e0d5b7e3`
Trạng thái trước audit: worktree sạch, `main` đang theo dõi `origin/main`.

## 1. Phạm vi và giới hạn

- Đã quét toàn bộ `340` file Java trong `code/backend/src/main/java/com/hotel/backend`.
- Đã đối chiếu package controller, service, implementation, repository, entity, config, scheduled job và các interface service.
- Bước này chỉ là static architecture audit. Không đổi code, endpoint, DTO, database schema hay workflow.
- Không coi mọi class dài là vi phạm SRP; chỉ ghi nhận khi trong class có nhiều nhóm trách nhiệm thay đổi vì các lý do khác nhau.
- Không coi mọi `switch`/`if` là vi phạm OCP; các mapping hữu hạn, ổn định như JWT token type hoặc mã phản hồi provider không được xếp thành lỗi.
- Spring Data repository là abstraction; việc inject repository không tự động bị coi là vi phạm DIP. Chỉ ghi nhận khi application service bị khóa trực tiếp vào adapter/provider cụ thể hoặc một service tổng quát phụ thuộc vào bề mặt quá rộng.
- Chưa chạy lại full test suite vì Bước 1 không có thay đổi mã nguồn. Lần chạy baseline trước đó bị dừng giữa chừng nên không được dùng làm bằng chứng pass/fail. Full suite là gate bắt buộc ngay trước và sau từng module ở Bước 2.

## 2. Các contract được đóng băng trong toàn bộ refactor

Các refactor sau này không được thay đổi:

- Tên, HTTP method, path, status code và thứ tự xử lý của REST endpoint.
- Tên field và cấu trúc request/response DTO.
- Tên bảng, cột, constraint và dữ liệu đã lưu.
- State transition và transaction boundary của Reservation, RoomHold, Payment, Refund, ledger, check-in, checkout và reconciliation.
- Quy tắc idempotency, webhook authentication/deduplication, thời điểm provider và thứ tự ghi event/payment/refund/audit.
- Quyền truy cập hiện tại.
- Nội dung và thứ tự side effect hiện tại như save, publish event, audit, email và release/convert hold.

## 3. Bản đồ điểm nóng định lượng

| File | Dòng | Dependency `private final` | Method phát hiện | Kết luận sơ bộ |
|---|---:|---:|---:|---|
| `service/Impl/ReservationServiceImpl.java` | 2687 | 18 | 81 | God service, rủi ro nghiệp vụ cao nhất |
| `service/PaymentRefundService.java` | 1858 | 13 | 68 | God service tài chính, nhiều nhánh theo refund channel |
| `service/ChatBotService.java` | 1666 | 3 | 68 | Một class ôm NLU, API client, cache, rate limit và Gemini |
| `service/SePayService.java` | 1394 | 12 | 53 | Provider adapter, webhook, matching, review queue và ledger orchestration trộn nhau |
| `controller/ReservationController.java` | 600 | 7 | 31 | Nhiều nhóm use case trong một controller |
| `service/PaymentService.java` | 580 | 10 | 31 | Payment creation/query/refund facade/mapping trộn nhau |
| `controller/PaymentController.java` | 566 | 13 | 29 | Payment, webhook, review event và refund operations trong một controller |
| `service/Impl/RoomServiceImpl.java` | 469 | 5 | 23 | CRUD, availability, room assignment và maintenance trộn nhau |
| `service/MediaAssetService.java` | 443 | 2 | 18 | Asset lifecycle, ownership/security, cleanup và rollback trộn nhau |
| `service/EmailService.java` | 443 | 5 | 24 | Query dữ liệu, dựng nội dung, quản lý verification state và SendGrid transport |
| `service/Impl/UserServiceImpl.java` | 441 | 8 | 18 | CRUD user, password, verification, session và customer-profile synchronization |
| `service/CheckoutReconciliationRequestService.java` | 407 | 7 | 14 | Request workflow, correction dispatch, auto-resolution, mapping và authorization |
| `service/ReservationAuditService.java` | 406 | 4 | 22 | Ghi log, sanitize, truy vấn, actor resolution và alert outbox |

Số dòng chỉ là tín hiệu; các kết luận dưới đây dựa trên nhóm method/dependency cụ thể.

## 4. SRP — Single Responsibility Principle

### SRP-01 — `ReservationServiceImpl` có quá nhiều lý do để thay đổi

**Mức:** Critical
**Bằng chứng:**

- `ReservationServiceImpl.java:77-94`: 18 dependency gồm 11 repository, event publisher, mapper, pricing, audit, customer claim, refund và refund recipient.
- `ReservationServiceImpl.java:113-436`: tạo reservation, kích hoạt/chuyển RoomHold, xử lý payment failure và recovery.
- `ReservationServiceImpl.java:447-818`: cả hai flow walk-in và payment option.
- `ReservationServiceImpl.java:820-1168`: read model, guest lookup, cancellation, approve/reject và confirmation.
- `ReservationServiceImpl.java:1170-1490`: availability, room assignment, check-in và checkout.
- `ReservationServiceImpl.java:1492-1679`: reject confirmation, phí checkout, refund và no-show.
- `ReservationServiceImpl.java:1682-1974`: admin update, final-payment projection, reconciliation và invoice snapshot.

**Vì sao vi phạm:** class thay đổi khi policy reservation, inventory/hold, cancellation, walk-in, check-in, checkout, pricing, invoice, refund mapping hoặc audit thay đổi.

**Hướng refactor an toàn đề xuất:** chỉ extract nguyên khối method/helper sang các collaborator nhỏ như `ReservationCreationCoordinator`, `RoomHoldLifecycle`, `WalkInCoordinator`, `ReservationCancellationCoordinator`, `StayLifecycleCoordinator`, `CheckoutProjection` và `ReservationInvoiceSnapshotService`. Giữ một facade tương thích `ReservationService`; không viết lại logic và không đổi transaction/order.

### SRP-02 — `PaymentRefundService` trộn toàn bộ vòng đời refund

**Mức:** Critical
**Bằng chứng:**

- `PaymentRefundService.java:114-126`: 13 dependency gồm repository, provider, config, transaction manager, cipher, audit và media.
- `PaymentRefundService.java:135-598`: tạo request, cancellation policy detail, phân bổ tiền và sinh refund code.
- `PaymentRefundService.java:599-824`: submit/reconcile/retry provider refund.
- `PaymentRefundService.java:826-1043`: cancel/replacement/operational details.
- `PaymentRefundService.java:1045-1319`: proof, cash completion, QR/manual transfer, SePay outgoing và manual fallback.
- `PaymentRefundService.java:1322-1509`: operational queue, response enrichment và ledger/net-paid summaries.
- `PaymentRefundService.java:1511-1857`: provider result, transaction boundary, completion, reservation finalization và audit.

**Vì sao vi phạm:** allocation policy, provider execution, evidence, reconciliation, ledger query, lifecycle transition, DTO mapping và audit có lý do thay đổi độc lập.

**Hướng refactor an toàn đề xuất:** trước tiên extract pure mapper/query calculator; sau đó move nguyên khối provider/manual/cash handlers. Điểm chốt `completeRefund(...)` và thứ tự finalization/audit phải giữ nguyên tuyệt đối.

### SRP-03 — `SePayService` vừa là adapter vừa là application orchestrator

**Mức:** Critical
**Bằng chứng:**

- `SePayService.java:91-102`: config, mapper, `EntityManager`, ba repository, provider-event service, reservation/refund services, audit và event publisher.
- `SePayService.java:104-235`: validate config, payment code/QR instructions và webhook authentication.
- `SePayService.java:236-291`: webhook intake và reconciliation.
- `SePayService.java:293-579`: review queue, match/manual reconcile/ignore/refund.
- `SePayService.java:581-800`: durable dedup, incoming/outgoing dispatch và event persistence.
- `SePayService.java:801-1017`: outgoing refund match và incoming payment application.
- `SePayService.java:1019-1393`: dedup identity, provider time parsing, merchant matching, payment lookup, additional transfer và hashing.

**Vì sao vi phạm:** cryptographic verification, provider parsing, dedup, payment matching, refund matching, review operations, persistence và domain orchestration bị khóa trong cùng class.

**Hướng refactor an toàn đề xuất:** tách parser/authenticator/dedup-key builder/read mapper trước; webhook transaction orchestrator giữ nguyên thứ tự lock/save/publish. Không refactor provider flow cùng lượt với Reservation.

### SRP-04 — `ChatBotService` ôm toàn bộ chatbot stack

**Mức:** High
**Bằng chứng:**

- `ChatBotService.java:139-239`: một entry point điều phối sanitize, rate limit, prompt injection, intent, reservation/availability, context và Gemini.
- `ChatBotService.java:257-327`: input sanitation, rate limiting và prompt-injection checks.
- `ChatBotService.java:329-713`: FAQ, policy, room type, facility, payment và location answers.
- `ChatBotService.java:715-1259`: availability intent, date/time parser, reservation action và formatter.
- `ChatBotService.java:1261-1369`: gọi nội bộ các API và theo dõi lỗi.
- `ChatBotService.java:1395-1554`: context cache và prompt construction.
- `ChatBotService.java:1556-1645`: Gemini transport/response parsing.

**Vì sao vi phạm:** security/rate-limit, NLU/parser, hotel knowledge, internal API access, cache, prompt và provider transport thay đổi độc lập.

**Hướng refactor an toàn đề xuất:** extract từng helper hiện hữu không sửa nội dung: `ChatInputGuard`, `ChatRateLimiter`, `HotelKnowledgeClient`, `AvailabilityIntentParser`, `ChatContextProvider`, `GenerativeChatClient`.

### SRP-05 — Hai controller nghiệp vụ quá rộng

**Mức:** High
**Bằng chứng:**

- `ReservationController.java:65-214`: availability, online create và hai walk-in routes.
- `ReservationController.java:216-372`: customer read/cancel/refund-recipient và staff cancellation.
- `ReservationController.java:374-539`: confirmation, check-in/out, fee/refund/no-show và manual hold release.
- `ReservationController.java:541-599`: final payment, reconciliation, invoice, audit và update.
- `PaymentController.java:61-73`: 13 dependency.
- `PaymentController.java:85-141`: create payment và SePay webhook.
- `PaymentController.java:145-252`: SePay review/recovery/match/ignore/refund operations.
- `PaymentController.java:257-379`: cash, legacy VNPay callbacks và payment queries.
- `PaymentController.java:382-565`: refund lifecycle, recipient, fallback, cash/manual complete, reconcile/retry/cancel.

**Vì sao vi phạm:** transport adapter thay đổi vì nhiều actor/use-case không liên quan. Đây là SRP ở adapter layer dù endpoint contract vẫn phải giữ nguyên.

**Hướng refactor an toàn đề xuất:** move handler method nguyên trạng sang nhiều controller class có cùng class-level path; giữ nguyên từng mapping, annotation, DTO, status và authorization. Chỉ làm sau khi có endpoint contract tests.

### SRP-06 — `PaymentService` vừa command, query, validation và response mapping

**Mức:** High
**Bằng chứng:**

- `PaymentService.java:46-55`: 10 dependency.
- `PaymentService.java:59-266`: online, walk-in SePay, abandon và cash creation.
- `PaymentService.java:268-335`: access-controlled queries, replay, latest walk-in và refund facade.
- `PaymentService.java:337-450`: reservation/payment validation, purpose resolution, amount/net-paid và refund operations.
- `PaymentService.java:453-577`: mapping/instructions, reusable pending lookup và balance calculation.

**Hướng refactor an toàn đề xuất:** extract response mapper, access policy và balance calculator trước; command orchestration giữ nguyên.

### SRP-07 — `RoomServiceImpl` trộn catalogue, assignment và maintenance

**Mức:** Medium
**Bằng chứng:**

- `RoomServiceImpl.java:61-193`: CRUD/search/availability.
- `RoomServiceImpl.java:195-292`: room/cleaning status và transfer phòng đang check-in.
- `RoomServiceImpl.java:294-360`: active reservation query và maintenance lifecycle.
- `RoomServiceImpl.java:362-463`: paging, validation, maintenance log và audit.

**Hướng refactor an toàn đề xuất:** tách query, maintenance và occupied-room transfer collaborator; giữ `RoomService` facade cho controller.

### SRP-08 — `UserServiceImpl` trộn account administration và identity lifecycle

**Mức:** Medium
**Bằng chứng:**

- `UserServiceImpl.java:67-260`: query, create và update user.
- `UserServiceImpl.java:284-345`: self-service password, delete và admin reset password.
- `UserServiceImpl.java:347-395`: customer-profile synchronization và page mapping.
- `UserServiceImpl.java:397-434`: email verification/resend và session invalidation.

**Hướng refactor an toàn đề xuất:** extract mapper/profile synchronizer/session invalidator; không thay authentication contract.

### SRP-09 — `EmailService` trộn composition, data access, state và transport

**Mức:** Medium
**Bằng chứng:**

- `EmailService.java:82-86`: phụ thuộc trực tiếp hai SendGrid client và hai repository nghiệp vụ.
- `EmailService.java:105-199`: generic/contact/password/audit/verification mail.
- `EmailService.java:200-255`: tự query reservation rồi dựng booking content.
- `EmailService.java:257-383`: chọn template, dựng `Mail`, gọi SendGrid và diễn giải status.
- `EmailService.java:386-440`: rollback verification state và helper template/config.

**Hướng refactor an toàn đề xuất:** tách `EmailGateway`, booking-email data assembler và verification coordinator. Template renderer hiện hữu tiếp tục được tái sử dụng.

### SRP-10 — `MediaAssetService` trộn lifecycle, authorization và storage transaction cleanup

**Mức:** Medium
**Bằng chứng:**

- `MediaAssetService.java:65-180`: temporary registration và replace/release references.
- `MediaAssetService.java:207-239`: claim financial evidence và access checks.
- `MediaAssetService.java:241-324`: cleanup và managed-reference ownership.
- `MediaAssetService.java:326-430`: orphaning, owner authorization và rollback cleanup.

**Hướng refactor an toàn đề xuất:** extract owner policy và cleanup component; giữ transaction synchronization nguyên trạng.

### SRP-11 — `ReservationAuditService` vừa ghi, đọc, sanitize và phát cảnh báo

**Mức:** Medium
**Bằng chứng:**

- `ReservationAuditService.java:62-190`: nhiều entry point ghi audit và actor resolution.
- `ReservationAuditService.java:192-244`: alert outbox và recipient config.
- `ReservationAuditService.java:246-337`: reservation-specific và global audit queries.
- `ReservationAuditService.java:339-397`: actor/display mapping và JSON sanitization.

**Hướng refactor an toàn đề xuất:** tách writer, query service, sanitizer và alert policy nhưng giữ format dữ liệu/audit action hiện tại.

## 5. OCP — Open/Closed Principle

### OCP-01 — Refund behavior phân nhánh lặp lại theo `RefundChannel`

**Mức:** Critical
**Bằng chứng:** `PaymentRefundService.java:233-261`, `449-482`, `616-617`, `705-706`, `759-795`, `947-959`, `1085-1087`, `1156-1161`, `1385-1406`, `1616-1621`, `1816-1821`.

Thêm hoặc thay đổi một refund channel buộc sửa nhiều vùng allocation, trạng thái đầu, recipient, submit/retry, proof, summary và completion evidence.

**Đề xuất:** strategy registry theo channel, nhưng chỉ move nguyên các nhánh hiện hữu. Không chuẩn hóa lại status hay thay điều kiện trong cùng lượt.

### OCP-02 — Walk-in payment option nằm trong orchestration chính

**Mức:** High
**Bằng chứng:** `ReservationServiceImpl.java:678`, `774-816`.

Các nhánh `UNPAID`, `CASH`, `SEPAY` nằm trực tiếp trong flow create-and-check-in; thêm option mới phải sửa method tài chính/room assignment rất nhạy cảm.

**Đề xuất:** `WalkInPaymentHandler` theo option, sau khi có characterization tests cho từng option.

### OCP-03 — SePay dispatch theo chuỗi `transferType`

**Mức:** High
**Bằng chứng:** `SePayService.java:626-630`, `756-763`, `801-849`.

Incoming payment và outgoing refund cùng đi qua một service và dispatch bằng chuỗi `"in"`/`"out"`.

**Đề xuất:** parser chuẩn hóa type và handler registry; giữ nguyên dedup key, transaction boundary và event outcome.

### OCP-04 — Checkout correction dispatch dùng `switch`

**Mức:** Medium
**Bằng chứng:** `CheckoutReconciliationRequestService.java:209-216`.

Thêm correction type phải sửa service resolve. Hiện `FEE_CORRECTION` bị từ chối có chủ đích và `LINK_EXISTING_PAYMENT` được xử lý.

**Đề xuất:** handler theo correction type; tuyệt đối không biến refactor thành mở quyền fee correction hoặc force checkout.

### OCP-05 — OAuth provider lookup được hardcode trong properties

**Mức:** Low
**Bằng chứng:** `OAuthProperties.java:36-39`.

Hiện chỉ Google/Facebook nên tác động nhỏ. Nếu thêm provider phải sửa switch.

**Đề xuất:** map credentials theo registration id; chỉ làm nếu không đổi binding/config contract hiện tại.

## 6. LSP — Liskov Substitution Principle

**Kết luận:** Chưa phát hiện vi phạm LSP được xác nhận trong static audit.

Đã kiểm tra:

- Các implementation của `AuthenticationService`, `FacilityService`, `GalleryService`, `GuestService`, `JwtService`, `ReservationService`, `ReviewService`, `RoomService`, `RoomTypeService`, `UserService`.
- Không có method implementation rỗng hoặc `UnsupportedOperationException`.
- Không thấy implementation nào công khai siết precondition hoặc đổi kiểu kết quả trái hợp đồng interface một cách có thể kết luận chắc chắn từ code.

Không tạo “vi phạm giả” chỉ để đủ danh mục. Bề mặt interface quá rộng được ghi nhận ở ISP. Việc xác nhận behavioral substitutability sẽ dựa vào full suite sau mỗi extraction ở Bước 2.

## 7. ISP — Interface Segregation Principle

### ISP-01 — `ReservationService` là interface quá béo

**Mức:** Critical
**Bằng chứng:** `ReservationService.java:25-93` có 26 method declaration, bao gồm creation, walk-in, RoomHold/payment recovery, queries, cancellation, confirmation, availability, check-in/out, refund/no-show, update, final payment, reconciliation và invoice.

**Tác động:** controller, payment, SePay và reconciliation phụ thuộc vào một contract rộng hơn nhu cầu thật.

**Đề xuất:** tạo các interface nhỏ theo client (`ReservationCommandUseCases`, `ReservationQueryUseCases`, `RoomHoldLifecyclePort`, `StayLifecycleUseCases`, `CheckoutProjectionPort`) và giữ facade `ReservationService` tương thích trong giai đoạn chuyển tiếp.

### ISP-02 — `RoomService` gộp catalogue, assignment và maintenance

**Mức:** High
**Bằng chứng:** `RoomService.java:14-42` có 15 method từ CRUD/query tới transfer phòng đang ở và maintenance lifecycle.

**Đề xuất:** interface query, management, assignment và maintenance nhỏ; controller hiện tại vẫn có thể inject facade.

### ISP-03 — `UserService` gộp account CRUD, password và verification

**Mức:** Medium
**Bằng chứng:** `UserService.java:13-23` có 10 method cho query/CRUD, password/reset, verify/resend và admin create.

**Đề xuất:** tách `UserQueryService`, `UserAdministrationService`, `PasswordService`, `EmailVerificationService`; giữ public behavior hiện tại.

Các interface CRUD 4-7 method còn lại chưa có bằng chứng đủ mạnh để coi là “fat interface”.

## 8. DIP — Dependency Inversion Principle

### DIP-01 — Chatbot phụ thuộc trực tiếp provider transport

**Mức:** High
**Bằng chứng:** `ChatBotService.java:129` inject `WebClient.Builder`; `ChatBotService.java:1556-1645` chứa trực tiếp Gemini request/response handling.

**Đề xuất:** application-level `GenerativeChatClient` port; WebClient/Gemini là adapter. Giữ nguyên prompt, timeout, fallback và parsing.

### DIP-02 — Email application service phụ thuộc trực tiếp SendGrid

**Mức:** High
**Bằng chứng:** `EmailService.java:82-83` inject trực tiếp `SendGrid`; `EmailService.java:317-383` dựng SendGrid `Mail`, `Request` và xử lý status.

**Đề xuất:** `EmailDeliveryGateway` port trả về cùng delivery outcome; SendGrid adapter chứa SDK-specific code.

### DIP-03 — Refund application service phụ thuộc provider/config cụ thể

**Mức:** Critical
**Bằng chứng:** `PaymentRefundService.java:119-123` phụ thuộc trực tiếp `VNPayRefundGateway`, `VNPayConfig`, `SePayConfig` và `PlatformTransactionManager`.

**Đề xuất:** provider execution/query port và payout-instruction provider. `requiresNew(...)` cùng transaction propagation hiện tại phải được bảo toàn.

### DIP-04 — Payment orchestration phụ thuộc concrete financial services

**Mức:** High
**Bằng chứng:** `PaymentService.java:46-55` phụ thuộc concrete `SePayService`, `PaymentRefundService`, `PaymentSessionExpiryService` cùng nhiều persistence abstractions.

**Đề xuất:** các port hẹp cho payment instruction, refund ledger query và session expiry; không đổi sequence create transaction → hold → response/event.

### DIP-05 — SePay orchestration bị khóa vào JPA và domain service rộng

**Mức:** Critical
**Bằng chứng:** `SePayService.java:91-102` phụ thuộc `EntityManager`, repository, `ReservationService`, `PaymentRefundService` và audit/event infrastructure trong cùng class.

**Đề xuất:** tách high-level webhook use case khỏi persistence/provider helper qua ports hẹp. Repository vẫn có thể là adapter abstraction; không thay lock query hay flush order.

### DIP-06 — Scheduler phụ thuộc trực tiếp SePay client cụ thể

**Mức:** Medium
**Bằng chứng:** `SePayReconciliationScheduler.java:25-26` inject `SePayConfig` và `SePayApiClient`.

**Đề xuất:** `ProviderTransactionFeed` port; scheduler chỉ biết lookback/cursor use case. Adapter SePay giữ request contract.

### Các điểm đã làm đúng, không đưa vào violation

- `FileStorageService.java:26` phụ thuộc `UploadStorage` abstraction thay vì storage provider cụ thể.
- Repository chính là Spring Data interface, không phải concrete construction.
- Constructor injection là chuẩn chủ đạo; hai `@Autowired` tại OAuth ticket services vẫn là constructor injection hỗ trợ test clock.
- `ApplicationEventPublisher` là abstraction của framework, không phải `new` concrete dependency.

## 9. Thứ tự triển khai đề xuất để giảm rủi ro

Đây là thứ tự **an toàn để thực thi**, không phải thứ tự mức độ nghiêm trọng:

| Phase | Module | Lý do chọn thứ tự | Gate bắt buộc |
|---|---|---|---|
| 0 | Baseline/contract freeze | Xác nhận chính xác số test hiện tại và snapshot endpoint/DTO | Full suite pass trước mọi refactor |
| 1 | Chatbot | Nhiều lợi ích SRP/DIP, ít chạm ledger/reservation state | Full suite; đặc biệt `ChatBotServiceTest` |
| 2 | Room/User/Email/Media | Tách mapper/policy/adapter ở domain ít nhạy cảm hơn tài chính | Full suite sau từng module, không gộp |
| 3 | Controller + interface segregation | Giữ endpoint y nguyên nhưng giảm bề mặt dependency | Endpoint/security contract tests + full suite |
| 4 | Reservation | Rủi ro cao; bắt đầu từ mapper/query/projection rồi mới coordinator | Full suite sau từng extraction; dừng ngay khi fail |
| 5 | Payment/Refund/SePay/Reconciliation | Rủi ro tiền, idempotency và concurrency cao nhất; làm cuối khi test harness đã chắc | Full suite + idempotency/concurrency/provider tests sau từng module |

Không nên refactor `ReservationServiceImpl`, `PaymentRefundService` và `SePayService` trong cùng một phase hoặc một commit.

## 10. Điều kiện dừng và quy trình cho Bước 2

Sau khi chủ dự án duyệt module:

1. Ghi baseline: commit, worktree, danh sách/số test và full-suite result.
2. Chỉ chọn một module nhỏ.
3. Di chuyển/rename/extract nguyên code; không viết lại logic.
4. Kiểm tra `git diff` để bảo đảm endpoint, DTO, entity/schema và branch condition không đổi.
5. Chạy full suite.
6. Nếu một test fail hoặc output/order khác đi: dừng ngay, không sang module tiếp theo và không “sửa test cho pass”.
7. Báo cáo diff + test result để chủ dự án duyệt trước module kế tiếp.

## 11. Trạng thái

- Audit Bước 1: **COMPLETED**
- Refactor theo phạm vi an toàn đã được duyệt: **COMPLETED**
- REST/DTO/database/workflow changes: **NONE**
- Baseline trước refactor: **259 tests pass**
- Gate cuối sau refactor: **299 tests pass, 0 failures, 0 errors, BUILD SUCCESS**
- Chi tiết triển khai và phạm vi cố ý giữ nguyên: xem `REFACTOR_SUMMARY.md`.
- Các phát hiện logic/workflow tách riêng: xem `REFACTOR_FINDINGS.md`.
