# Báo cáo triển khai Hotel Reservation – Payment Platform

## Trạng thái hiện tại

Project mới đã chuyển sang PostgreSQL 16 làm database duy nhất.

| Hạng mục | Trạng thái |
| --- | --- |
| Runtime JDBC/Flyway | PostgreSQL-only |
| Flyway source | `classpath:db/migration-postgres` |
| Schema | V1 baseline, 34 bảng ứng dụng |
| Tối ưu schema | V2 index/constraint PostgreSQL |
| Test database | PostgreSQL Testcontainers; H2 chỉ ở test scope |
| Backend test | 208 passed, 0 failure/error/skip |
| Frontend build | Production build passed, 39 routes |
| Health smoke | `/actuator/health` trả `UP` với PostgreSQL |

## Quyết định database

- Chỉ dùng driver PostgreSQL trong runtime artifact.
- Chỉ có một thư mục migration được Flyway quét: `db/migration-postgres`.
- Không còn migration, fixture, preflight hoặc script vận hành cho database
  thay thế trong project.
- H2 không được đóng gói vào runtime; chỉ dùng cho test nhanh.
- Mọi thay đổi schema mới phải tạo migration PostgreSQL riêng và chạy qua
  `ddl-auto=validate` cùng integration test.

## Các aggregate nghiệp vụ đã được kiểm tra

- Reservation, RoomHold và hard availability chống overbooking.
- Payment allocation: expected/received/accepted/refund-required.
- SePay event deduplication, retry, review và reconciliation cursor.
- Refund ledger, recipient trực tiếp, cash/bank completion và cancellation.
- Idempotency cho mutation tài chính/vận hành và concurrency 10 request.
- Walk-in `CASH`/`SEPAY`/`UNPAID`, check-in nhiều loại phòng và checkout.
- Invoice snapshot bất biến, audit log và các timestamp tài chính UTC.

## Gate trước khi triển khai

1. Chạy `mvn test` và profile `postgres-migration-test` từ cùng revision.
2. Chạy `db/postgres/post-cutover-validate.sql` trên backup/staging clone.
3. Đối soát row count và tổng tiền payment/refund/invoice.
4. Xác minh credential/merchant SePay, load/concurrency và operator UAT.
5. Kiểm tra backup/restore và giữ rollback snapshot trước khi mở traffic.

Tài liệu vận hành chi tiết: `docs/database/postgresql.md`.
