# Luxury Hotel - Hotel Management Frontend ⚜

Frontend của hệ thống Quản lý Khách sạn Cao cấp **Luxury Hotel**. Dự án được xây dựng trên nền tảng **Next.js 15 (App Router)** kết hợp với **TypeScript**, **TailwindCSS** và hệ thống thiết kế premium (glassmorphic UI, custom animations). Hướng dẫn dưới đây giúp bạn khởi chạy ứng dụng một cách nhanh nhất.

## 🛠️ Yêu cầu hệ thống

Trước khi bắt đầu, hãy đảm bảo máy tính của bạn đã cài đặt:
- **Node.js** (Khuyến nghị phiên bản LTS từ 18 trở lên)
- **pnpm** (Package Manager chính của dự án)
  - *Nếu chưa cài đặt pnpm, bạn có thể chạy lệnh:* `npm install -g pnpm`

---

## 🚀 Hướng dẫn khởi chạy dự án

Làm theo các bước sau sau khi pull code về:

### Bước 1: Di chuyển vào thư mục Frontend
```bash
cd code/frontend
```

### Bước 2: Cài đặt các thư viện (Dependencies)
```bash
pnpm install
```

### Bước 3: Khởi chạy môi trường phát triển (Development Server)
```bash
pnpm run dev
```

Sau khi chạy lệnh trên thành công, ứng dụng Frontend sẽ được chạy tại địa chỉ:
👉 **[http://localhost:3000](http://localhost:3000)**

---

## 💡 Lưu ý về cấu hình API (Environment Variables)

Mặc định, Next.js proxy `/backend_proxy` kết nối tới Backend tại `http://localhost:8080`.
Trình duyệt gọi cùng origin qua proxy để cookie xác thực và CORS ổn định hơn.

Nếu backend chạy tại host khác, bạn có thể:
1. Tạo một file tên là `.env.local` ở thư mục `code/frontend`.
2. Cấu hình URL nội bộ mà Next.js server có thể truy cập:
   ```env
   BACKEND_INTERNAL_URL=http://localhost:8080
   ```

Chỉ dùng `NEXT_PUBLIC_API_URL=https://api.example.com` khi muốn trình duyệt gọi
thẳng backend và backend đã cấu hình CORS/cookie cho domain frontend.

### OAuth Google/Facebook

Luồng OAuth là điều hướng cấp trang nên phải bắt đầu trực tiếp tại origin backend,
không đi qua `/backend_proxy`. Khai báo origin công khai của backend trong
`.env.local`:

```env
NEXT_PUBLIC_BACKEND_URL=http://localhost:8080
```

Giá trị này phải cùng origin với callback OAuth mà backend đăng ký với Google hoặc
Facebook. Frontend gọi API thông thường qua `NEXT_PUBLIC_API_URL` hoặc
`/backend_proxy`; riêng nút đăng nhập mạng xã hội điều hướng tới
`${NEXT_PUBLIC_BACKEND_URL}/auth/oauth/authorize/{provider}`. Backend sẽ kiểm tra
provider đã được cấu hình rồi mới chuyển sang endpoint OAuth2 của Spring, nhờ đó
cookie `JSESSIONID`/OAuth state được tạo trên đúng backend origin.

Khi frontend và backend dùng hai hostname khác nhau ở production, giữ
`NEXT_PUBLIC_BACKEND_URL` là public origin của backend để bắt đầu redirect OAuth,
nhưng đặt `NEXT_PUBLIC_API_URL=/backend_proxy`. Trình duyệt luôn gọi API qua cùng
origin Vercel; Next.js chuyển tiếp tới `BACKEND_INTERNAL_URL`, nên refresh cookie
HttpOnly không phụ thuộc third-party cookie giữa Vercel và Render.

Backend phải chuyển người dùng về
`<FRONTEND_BASE_URL>/oauth/callback?status=success&ticket=<one-time-code>` hoặc trả
một `error` code an toàn. Callback đổi mã dùng một lần qua `/backend_proxy`, nhận
refresh cookie HttpOnly và chỉ giữ access token trong bộ nhớ. JWT, refresh token và
provider token không xuất hiện trong URL hoặc localStorage.
