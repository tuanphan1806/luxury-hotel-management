# PostgreSQL database operations

Project này chỉ hỗ trợ PostgreSQL. Backend, Flyway, Docker Compose và các
integration test đều dùng PostgreSQL 16.

## Cấu hình local

- Database: `hotelmanagement`
- User mặc định local: `hotel`
- Host port: `5433` (container dùng `5432`)
- JDBC: `jdbc:postgresql://localhost:5433/hotelmanagement`
- Flyway: `classpath:db/migration-postgres`

Khởi động database:

```powershell
docker compose up -d postgres
```

Khởi động backend:

```powershell
cd code/backend
.\mvnw.cmd spring-boot:run
```

## Schema và tối ưu

- `V1__baseline_schema.sql`: baseline 34 bảng của ứng dụng.
- `V2__postgresql_optimization.sql`: composite/partial index, các kiểm tra
  dữ liệu tài chính và loại bỏ index trùng chức năng.
- `V3__postgresql_schema_cleanup.sql`: chuẩn hóa PostgreSQL mà không thay đổi
  checksum của V1/V2 đã có thể được áp dụng ở local/staging.
- `post-cutover-finalize.sql`: đồng bộ toàn bộ identity sequence theo ID lớn
  nhất đã import và chạy `ANALYZE`.
- `post-cutover-validate.sql`: kiểm tra foreign key, constraint và thống kê
  payment/refund/invoice sau triển khai; script sẽ từ chối cutover nếu sequence
  vẫn đứng sau ID đã import.

Không bật `ddl-auto=create` hoặc tự tạo bảng ngoài Flyway. Mọi thay đổi schema
phải đi qua migration PostgreSQL mới, có kiểm tra Hibernate
`ddl-auto=validate` và integration test.

### Checksum baseline local/staging

Nếu một database PostgreSQL local đã chạy baseline cũ trước khi file V1 được
chỉnh checksum, Flyway sẽ dừng với `Migration checksum mismatch`. Đó là hàng rào
an toàn; không tắt `validate-on-migrate`. Sau khi backup và xác nhận Hibernate
`ddl-auto=validate` thành công, chạy một lần:

```powershell
psql -v ON_ERROR_STOP=1 $env:DATABASE_URL -f db/postgres/repair-local-baseline.sql
```

Script có guard checksum cũ cụ thể và sẽ từ chối mọi database khác. Không chạy
script này trên production nếu chưa có review/backup riêng; database production
phải dùng quy trình Flyway repair được phê duyệt.

## Chuyển dữ liệu từ hệ thống cũ

Đây là cutover chéo database, không phải nâng cấp tại chỗ. Không sao chép bảng
`flyway_schema_history` từ MySQL và không trỏ Flyway PostgreSQL vào database cũ.
Luôn tạo một PostgreSQL database mới, để Flyway tạo schema PostgreSQL rồi mới
copy dữ liệu nghiệp vụ với ID gốc.

Thứ tự bắt buộc khi cần giữ dữ liệu cũ:

1. Chặn ghi ở ứng dụng nguồn và tạo backup nhất quán có thể restore.
2. Chạy Flyway V1 -> phiên bản mới nhất trên PostgreSQL trống.
3. Import dữ liệu theo thứ tự cha trước, con sau; không import
   `flyway_schema_history`.
4. Khi ứng dụng vẫn đang dừng ghi, chạy:

   ```powershell
   psql -v ON_ERROR_STOP=1 $env:DATABASE_URL -f db/postgres/post-cutover-finalize.sql
   psql -v ON_ERROR_STOP=1 $env:DATABASE_URL -f db/postgres/post-cutover-validate.sql
   ```

5. So sánh số dòng và tổng tiền giữa nguồn/đích, sau đó chạy smoke test và UAT
   trước khi mở traffic.

`post-cutover-finalize.sql` là bắt buộc nếu dữ liệu được import với ID tường
minh. PostgreSQL không tự tăng identity sequence khi `COPY`/`INSERT` cung cấp ID;
bỏ qua bước này có thể làm lần ghi đầu tiên sau cutover lỗi duplicate key.

## Backup, restore và cutover

1. Tạo backup nhất quán của database PostgreSQL và kiểm tra restore trên một
   database tạm.
2. Chạy toàn bộ `mvn test` và profile `postgres-migration-test` từ cùng một
   revision với artifact triển khai.
3. Chạy `post-cutover-finalize.sql`, rồi `post-cutover-validate.sql` trên
   database staging/production sau khi import hoàn tất.
4. Đối soát số dòng và tổng tiền của payment, refund, invoice trước khi mở
   traffic trở lại.
5. Giữ backup bất biến trong suốt thời gian UAT và theo dõi scheduler,
   reconciliation, review queue và các khoản hoàn tiền chờ xử lý.

Database bên ngoài không bị xóa tự động bởi ứng dụng. Việc hủy hẳn một server
hoặc bản backup cũ phải do người vận hành thực hiện sau khi đối soát và ký xác
nhận rollback.

## Kiểm tra nhanh

```powershell
docker compose config --quiet
docker compose up -d --build
docker compose ps
cd code/backend
.\mvnw.cmd test
.\mvnw.cmd -Ppostgres-migration-test verify
```

Profile migration kiểm tra PostgreSQL 16 thật, gồm clean migration, Hibernate
schema validation, native query, JPQL trên enum, 10 retry đồng thời và tình
huống import ID legacy rồi ghi bản ghi mới.
