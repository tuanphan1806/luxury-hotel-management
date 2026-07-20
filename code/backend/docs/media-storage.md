# Lưu trữ ảnh production

## Lựa chọn mặc định

Project dùng `APP_UPLOAD_STORAGE=cloudinary` ở profile `prod` vì dữ liệu upload hiện tại đều là ảnh (avatar, tiện ích, loại phòng và gallery). Cloudinary cung cấp object storage, HTTPS CDN và quản lý/xóa asset; backend vẫn kiểm tra file và ký request nên `CLOUDINARY_API_SECRET` không bao giờ đi xuống trình duyệt.

Kiến trúc không phụ thuộc Cloudinary trực tiếp ở tầng nghiệp vụ:

- `local`: dev/test hoặc một server có persistent volume.
- `cloudinary`: mặc định production cho workload chỉ gồm ảnh.
- `s3`: AWS S3, MinIO hoặc Cloudflare R2 khi cần kiểm soát bucket/hạ tầng.

Tất cả adapter triển khai `UploadStorage`, vì vậy API `/files/upload` và dữ liệu `imageUrl` không đổi khi chuyển nhà cung cấp.

## Cấu hình Cloudinary

Không commit credential vào Git. Cấu hình secret trên môi trường triển khai:

```properties
SPRING_PROFILES_ACTIVE=prod
APP_UPLOAD_STORAGE=cloudinary
CLOUDINARY_CLOUD_NAME=your-cloud-name
CLOUDINARY_API_KEY=your-api-key
CLOUDINARY_API_SECRET=your-api-secret
CLOUDINARY_FOLDER=hotel-media
```

Nên tạo API key riêng cho ứng dụng, giới hạn quyền nếu gói Cloudinary hỗ trợ, bật MFA cho tài khoản quản trị và xoay key ngay khi bị lộ.

## Cấu hình S3-compatible thay thế

```properties
APP_UPLOAD_STORAGE=s3
APP_UPLOAD_BASE_URL=https://cdn.example.com
APP_UPLOAD_S3_BUCKET=hotel-production-media
APP_UPLOAD_S3_REGION=ap-southeast-1
APP_UPLOAD_S3_KEY_PREFIX=hotel-media
APP_UPLOAD_S3_VERIFY_BUCKET=true
```

AWS SDK dùng default credential provider chain. Trên AWS nên cấp IAM role cho workload thay vì lưu access key. Với MinIO/R2 có thể đặt thêm endpoint, path-style access và credential bằng biến môi trường tiến trình `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`. Không đặt các biến AWS này trong file `.env` của Spring vì `spring.config.import` không biến chúng thành biến môi trường hệ điều hành cho AWS SDK.

Bucket không cần public-write. Backend là bên duy nhất upload/xóa; chỉ CDN hoặc public-read policy được phép đọc ảnh đã xuất bản.

## Vòng đời asset

1. Backend xác minh MIME thực tế, decoder, kích thước tối đa, tổng pixel và dung lượng 5 MB.
2. Object được upload bằng UUID/public ID không ghi đè.
3. Một bản ghi `media_assets` trạng thái `TEMPORARY` được tạo và trả `assetId` cùng URL.
4. Khi tiện ích/loại phòng/avatar/gallery được lưu, asset chuyển sang `ACTIVE` và gắn đúng owner/purpose.
5. Khi thay hoặc xóa ảnh, asset cũ chuyển `ORPHANED`.
6. Scheduler chỉ xóa vật lý file `TEMPORARY`/`ORPHANED` sau thời gian grace period; lỗi nhà cung cấp được retry ở lần chạy sau.

## Chuyển bộ ảnh seed từ static lên Cloudinary

Đặt URL gốc theo đúng `cloud name` và folder Cloudinary, sau đó chỉ bật migration cho một lần chạy:

```properties
SEED_MEDIA_BASE_URL=https://res.cloudinary.com/<cloud-name>/image/upload/<folder>/static
APP_STATIC_MEDIA_MIGRATION_ENABLED=true
```

Runner upload ảnh trước, rồi `DataSeeder` mới cập nhật URL database. Public ID giữ nguyên cấu trúc
`<folder>/static/{avatar|facilities|galeries|room_types}/<tên-file>`, vì vậy chạy lại không tạo bản sao.
Sau khi thấy log `STATIC_MEDIA_MIGRATION_COMPLETED`, đặt ngay
`APP_STATIC_MEDIA_MIGRATION_ENABLED=false`. Không xóa ảnh gốc khỏi Git trước khi kiểm tra URL production.

Cơ chế này tránh endpoint xóa file chung nguy hiểm và dọn được file do người dùng upload rồi đóng form.

## Checklist triển khai

- Chạy Flyway migration `V9__media_asset_lifecycle.sql` trước khi nhận traffic.
- Không dùng local disk của container nếu không mount persistent shared volume.
- Giữ giới hạn reverse proxy/load balancer lớn hơn `UPLOAD_MAX_REQUEST_SIZE=6MB` một chút.
- Theo dõi tỷ lệ HTTP `413`, `429`, lỗi Cloudinary/S3 và số asset `TEMPORARY` quá hạn.
- Thiết lập cảnh báo quota/billing của Cloudinary hoặc bucket.
- Sao lưu database; Cloudinary/S3 giữ bytes nhưng quan hệ owner/purpose nằm trong `media_assets`.
- Nếu đổi provider, migrate object và URL theo batch; không thay trực tiếp URL đang được reservation/public page sử dụng mà không kiểm tra.
