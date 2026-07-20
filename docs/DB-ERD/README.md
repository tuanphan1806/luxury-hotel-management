# Database schema source

Schema chính thức của project nằm trong:

- `code/backend/src/main/resources/db/migration-postgres/V1__baseline_schema.sql`
- `code/backend/src/main/resources/db/migration-postgres/V2__postgresql_optimization.sql`

Các file SQL dump/sample rời không phải nguồn schema của ứng dụng. Khi cần
tạo lại ERD hoặc dữ liệu mẫu, hãy dùng Flyway và `DataSeeder` của backend để
đảm bảo kiểu dữ liệu, identity sequence, index và foreign key luôn đồng bộ.

