# Free-tier deployment — Luxury Hotel

## Phạm vi

Stack miễn phí được chọn cho bản đồ án/demo/UAT:

- Frontend Next.js: Vercel Hobby, root `code/frontend`.
- Backend Spring Boot: Render Free, Docker, region Singapore.
- Database: Neon PostgreSQL Free.
- Ảnh: Cloudinary Free hiện có.

Đây không phải kiến trúc production có SLA. Vercel Hobby chỉ dành cho mục đích
cá nhân/phi thương mại. Render Free sleep sau thời gian không có request, lần gọi
đầu có thể phải chờ backend khởi động. Webhook SePay có thể retry, nhưng không
được xem retry là cam kết luôn sẵn sàng. Chỉ dùng stack này cho demo, UAT và lưu
lượng thấp; không mở bán thật nếu chưa chấp nhận các giới hạn đó.

URL mặc định sau deploy:

- Frontend: `https://<vercel-project>.vercel.app`.
- Backend: `https://<render-service>.onrender.com`.

Hai URL khác site nên cookie refresh bắt buộc dùng `Secure` và `SameSite=None`.
CORS chỉ allow-list đúng URL Vercel, không dùng `*`.

## 1. Gate trước khi thao tác trên nền tảng

Vercel và Render chỉ deploy nội dung đã push lên Git provider. Trước khi import:

```powershell
cd code/backend
.\mvnw.cmd test
.\mvnw.cmd -Ppostgres-migration-test verify
docker build -t luxury-hotel-backend:release .

cd ../frontend
pnpm install --frozen-lockfile
pnpm run lint
pnpm run build
```

Sau đó review toàn bộ worktree và mở pull request theo luồng
`feature/* -> develop -> main`. Chỉ commit đã qua sprint review/UAT và quality
gates mới được merge vào `main`. Không deploy trực tiếp một worktree chứa file
cache hoặc thay đổi chưa được review.

### CI/CD theo GitFlow

- `.github/workflows/ci.yml` chạy branch-policy, backend/PostgreSQL và frontend
  gates cho pull request/push liên quan tới `develop` hoặc `main`.
- `develop` là nhánh tích hợp và tạo preview/UAT; `main` là production branch.
- Vercel liên kết GitHub để tự tạo preview cho pull request/nhánh không phải
  `main`, và production deployment khi commit đã được merge vào `main`.
- Render Blueprint dùng `autoDeployTrigger: checksPass`, vì vậy backend trên
  `main` chỉ deploy sau khi các GitHub checks của commit thành công.
- Ruleset của GitHub chặn xóa/force-push, yêu cầu pull request, lịch sử tuyến
  tính và chỉ cho phép squash hoặc rebase trên `develop`/`main`.
- `hotfix/*` phải tách từ `main`, sau khi kiểm thử phải merge vào cả `main` và
  `develop` để hai nhánh không bị lệch.

Không dùng biến production cho Vercel Preview. Render/Neon/Cloudinary/SePay
secret chỉ cấu hình trong dashboard tương ứng, tuyệt đối không commit vào Git.

## 2. Tạo Neon PostgreSQL Free

1. Đăng nhập Neon và chọn **New project**.
2. Project name: `luxury-hotel`.
3. Chọn region Singapore nếu tài khoản đang cung cấp; nếu không, chọn region gần
   Render Singapore nhất.
4. Chọn PostgreSQL 16 nếu màn hình cho phép. Migration đã được kiểm thử trên 16.
5. Database: `hotelmanagement`; tạo role riêng cho ứng dụng nếu không dùng role
   mặc định.
6. Mở **Connect**, chọn Java/JDBC và lấy **direct connection**, không chọn pooled
   connection trong lần deploy Flyway đầu tiên.
7. Bảo đảm URL có SSL, ví dụ:

```dotenv
DATABASE_URL=jdbc:postgresql://<host>.neon.tech/hotelmanagement?sslmode=require
DATABASE_USERNAME=<neon-role>
DATABASE_PASSWORD=<neon-password>
```

Không dán URI bắt đầu bằng `postgresql://` vào `DATABASE_URL`; Spring yêu cầu
`jdbc:postgresql://`. Không commit ba giá trị này vào Git.

Neon Free scale-to-zero khi không hoạt động. Lần kết nối đầu có thể chậm hơn bình
thường. Dữ liệu miễn phí có giới hạn, vì vậy phải xuất backup định kỳ ra máy cá
nhân; không coi cửa sổ restore ngắn của free tier là backup duy nhất.

## 3. Tạo frontend Vercel Hobby lần đầu

1. Đăng nhập Vercel bằng GitHub.
2. Chọn **Add New → Project**, import repository
   `tuanphan1806/luxury-hotel-management`.
3. Root Directory: `code/frontend`.
4. Framework Preset: Next.js.
5. Node.js: 24.
6. Install Command: `pnpm install --frozen-lockfile`.
7. Build Command: `pnpm run build`.
8. Thêm biến tạm để lấy URL Vercel trước:

```dotenv
BACKEND_INTERNAL_URL=https://placeholder.invalid
NEXT_PUBLIC_API_URL=https://placeholder.invalid
NEXT_PUBLIC_BACKEND_URL=https://placeholder.invalid
NEXT_PUBLIC_SITE_URL=https://<vercel-project>.vercel.app
HOTEL_NAME=Luxury Hotel
```

Lần deploy đầu có thể dùng URL project Vercel vừa được đề xuất. Sau khi Render
có URL thật, thay ba biến backend và redeploy frontend.

Không đưa database password, JWT key, SendGrid, SePay hoặc Cloudinary API secret
vào Vercel. Cloudinary URL công khai được dùng trong ảnh là an toàn; API secret
chỉ ở backend.

## 4. Tạo backend Render Free

Repository có `render.yaml` tại root. Trong Render:

1. Chọn **New → Blueprint**.
2. Kết nối cùng GitHub repository.
3. Blueprint Path: `render.yaml`.
4. Xác nhận service `luxury-hotel-backend`, plan `Free`, region `Singapore`.
5. Render sẽ hỏi các biến có `sync: false`. Điền theo danh sách dưới đây.

### Database và URL

```dotenv
DATABASE_URL=<Neon JDBC URL>
DATABASE_USERNAME=<Neon role>
DATABASE_PASSWORD=<Neon password>

FRONTEND_BASE_URL=https://<vercel-project>.vercel.app
BACKEND_BASE_URL=https://<render-service>.onrender.com
CORS_ALLOWED_ORIGINS=https://<vercel-project>.vercel.app
```

`render.yaml` đã đặt sẵn:

```dotenv
SPRING_PROFILES_ACTIVE=prod
AUTH_COOKIE_SECURE=true
AUTH_COOKIE_SAME_SITE=None
DB_POOL_MAX_SIZE=5
DB_POOL_MIN_IDLE=0
SPRING_DATA_JPA_REPOSITORIES_BOOTSTRAP_MODE=default
MALLOC_ARENA_MAX=2
APP_UPLOAD_STORAGE=cloudinary
APP_SEED_DEMO_USERS_ENABLED=false
```

Pool và JVM đã được giảm để phù hợp giới hạn RAM của Render Free. Không tăng các
giá trị này nếu chưa xem log memory và connection của Neon.

### Đăng nhập Google/Facebook

OAuth chạy hoàn toàn ở backend. Chỉ lưu các biến sau trên Render và `.env` local;
không đưa client secret lên Vercel hoặc vào biến `NEXT_PUBLIC_*`:

```dotenv
GOOGLE_OAUTH_CLIENT_ID=<google-web-client-id>
GOOGLE_OAUTH_CLIENT_SECRET=<google-web-client-secret>
FACEBOOK_OAUTH_CLIENT_ID=<meta-app-id>
FACEBOOK_OAUTH_CLIENT_SECRET=<meta-app-secret>
```

Google Web OAuth client phải có redirect URI:

```text
https://<render-service>.onrender.com/login/oauth2/code/google
```

Meta Facebook Login phải có valid OAuth redirect URI:

```text
https://<render-service>.onrender.com/login/oauth2/code/facebook
```

Thêm origin/site URL `https://<vercel-project>.vercel.app`. Google chỉ cần các
scope cơ bản `openid`, `email`, `profile`; không bật billing hoặc API trả phí.
Một provider chỉ hiện trên giao diện khi backend nhận đủ cả client ID và secret.

### Key bảo mật

Sinh ba giá trị riêng trên PowerShell:

```powershell
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
```

Điền theo thứ tự:

```dotenv
JWT_ACCESS_KEY=<ket-qua-64-byte-thu-nhat>
JWT_REFRESH_KEY=<ket-qua-64-byte-thu-hai>
REFUND_DATA_ENCRYPTION_KEY=<ket-qua-32-byte>
```

Phải backup refund encryption key ngoài Render. Mất key sẽ không giải mã được
thông tin tài khoản hoàn tiền đã lưu.

### Thông tin khách sạn và ngân hàng

```dotenv
HOTEL_ADDRESS=<dia-chi>
HOTEL_PHONE=<so-dien-thoai>
HOTEL_EMAIL=<email>
HOTEL_TAX_CODE=<ma-so-thue-hoac-NA>

MERCHANT_BANK_CODE=<ma-ngan-hang>
MERCHANT_BANK_NAME=<ten-ngan-hang>
MERCHANT_BANK_ACCOUNT_NUMBER=<so-tai-khoan>
MERCHANT_BANK_ACCOUNT_NAME=<chu-tai-khoan>
```

### SePay

Webhook đang dùng HMAC-SHA256, không cần `SEPAY_WEBHOOK_API_KEY`:

```dotenv
SEPAY_WEBHOOK_SECRET=<secret-HMAC-moi>
SEPAY_API_ACCESS_TOKEN=<token-doi-soat>
SEPAY_API_BANK_ACCOUNT_ID=<uuid-neu-co-hoac-de-trong>
```

Secret từng xuất hiện trong chat/log phải được rotate trước khi nhập Render.
Access token là bắt buộc nếu muốn reconciliation lấy bù giao dịch sau thời gian
Render sleep hoặc downtime.

### Cloudinary

```dotenv
CLOUDINARY_CLOUD_NAME=<cloud-name>
CLOUDINARY_API_KEY=<api-key>
CLOUDINARY_API_SECRET=<api-secret>
CLOUDINARY_FOLDER=hotel-media
SEED_MEDIA_BASE_URL=https://res.cloudinary.com/<cloud-name>/image/upload/hotel-media/static
```

Không cần chuyển ảnh và không cần upload lại ảnh cũ.

### Email/audit

```dotenv
VERIFICATION_SENDGRID_API_KEY=<sendgrid-key>
VERIFICATION_SENDGRID_FROM_EMAIL=<verified-sender>
VERIFICATION_SENDGRID_TEMPLATE_ID=<template-id>
PASSWORD_RESET_SENDGRID_TEMPLATE_ID=<template-id>
BOOKING_CONFIRMATION_SENDGRID_TEMPLATE_ID=<template-id>
CONTACT_REPLY_SENDGRID_TEMPLATE_ID=<template-id>
AUDIT_ALERT_SENDGRID_TEMPLATE_ID=<template-id>
AUDIT_ALERT_RECIPIENTS=<email-admin>
```

Sau khi deploy, kiểm tra:

```text
https://<render-service>.onrender.com/actuator/health
```

Phải trả HTTP 200 và trạng thái `UP`. Trong log, Flyway phải hoàn tất tới version
hiện tại trước khi Hibernate validate thành công.

## 5. Nối lại Vercel với Render

Vào Vercel → Project → Settings → Environment Variables và thay:

```dotenv
BACKEND_INTERNAL_URL=https://<render-service>.onrender.com
NEXT_PUBLIC_API_URL=/backend_proxy
NEXT_PUBLIC_BACKEND_URL=https://<render-service>.onrender.com
NEXT_PUBLIC_SITE_URL=https://<vercel-project>.vercel.app
```

Chọn Production và redeploy. Kiểm tra login, refresh cookie, ảnh Cloudinary và
các request API trong DevTools đi qua `/backend_proxy` và không có CORS error.

## 6. Khởi tạo dữ liệu lần đầu

Không đưa tài khoản mẫu password `123456` lên backend public. Các tài khoản đó
chỉ dùng local/dev/test.

Đối với database Neon trống, tạm đặt trên Render:

```dotenv
APP_STATIC_MEDIA_MIGRATION_ENABLED=true
APP_SEED_MASTER_DATA_ENABLED=true
APP_SEED_DEMO_USERS_ENABLED=false
APP_BOOTSTRAP_ADMIN_ENABLED=true
BOOTSTRAP_ADMIN_FULL_NAME=<ten-admin>
BOOTSTRAP_ADMIN_USERNAME=<username-rieng>
BOOTSTRAP_ADMIN_EMAIL=<email-admin>
BOOTSTRAP_ADMIN_PHONE=<so-dien-thoai>
BOOTSTRAP_ADMIN_PASSWORD=<mat-khau-rieng-tu-12-ky-tu>
```

Deploy một lần, đăng nhập và kiểm tra dữ liệu. Sau đó đặt ba switch migration,
master-data và bootstrap về `false`, xóa `BOOTSTRAP_ADMIN_PASSWORD`, rồi deploy
lại. Bootstrap không reset mật khẩu hoặc tự nâng quyền tài khoản đã tồn tại.

## 7. Cấu hình SePay

Trong SePay Webhooks:

1. URL: `https://<render-service>.onrender.com/api/payments/sepay/webhook`.
2. Event: **Cả hai** để nhận tiền vào và tiền ra.
3. Content-Type: JSON.
4. Authentication: HMAC-SHA256.
5. Secret phải khớp `SEPAY_WEBHOOK_SECRET` trên Render.
6. Ban đầu không bật bộ lọc “chỉ gửi khi có mã thanh toán”, tránh bỏ mất giao
   dịch hoàn tiền ra hoặc giao dịch cần review.
7. Test send phải trả 2xx. Chữ ký sai phải trả 401.

Vì Render Free sleep, test bắt buộc: để backend idle, tạo giao dịch thử, xác nhận
SePay retry hoặc reconciliation lấy bù mà không tạo ledger trùng. Nếu yêu cầu
webhook phản hồi ổn định theo thời gian thực, free backend không đáp ứng DoD.

## 8. Giới hạn miễn phí và vận hành

- Vercel Hobby: chỉ cá nhân/phi thương mại; project có thể pause khi vượt quota.
- Render Free: sleep sau 15 phút không có traffic, cold start có thể khoảng một
  phút, filesystem tạm thời và không có persistent disk.
- Neon Free: 0.5 GB mỗi project, compute scale-to-zero và cửa sổ restore ngắn.
- Cloudinary Free: 25 credits/tháng dùng chung cho storage, bandwidth và
  transformations.

Không dùng ping giả để lách cơ chế sleep. Không lưu refund proof hoặc ảnh upload
trên filesystem Render; project đã cấu hình Cloudinary cho mục đích này.

Theo dõi hằng ngày:

- Render logs, memory và cold-start failure.
- Neon storage/compute/connection usage.
- SePay webhook delivery/retry và reconciliation cursor.
- Refund pending, RoomHold overdue, audit email outbox.
- Vercel và Cloudinary quota.

## 9. Rollback

- Frontend: chọn deployment tốt gần nhất trong Vercel và Promote/Rollback.
- Backend: Render Free giữ số deployment rollback hạn chế; chọn revision trước.
- Database: không chạy Flyway `clean`, không sửa checksum để ép chạy. Khi cần
  restore, khôi phục vào database/branch mới, đối soát số dòng và tổng tiền rồi
  mới đổi connection.
- Trong downtime, giữ SePay API access token để reconciliation lấy bù; không tự
  tạo ledger thủ công để thay webhook.

Không xóa database local hoặc bản backup cũ cho đến khi Neon đã được restore thử,
đối soát tài chính và qua UAT vận hành.
