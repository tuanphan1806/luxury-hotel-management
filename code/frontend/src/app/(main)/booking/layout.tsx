import type { Metadata } from "next";

const title = "Thanh toán đặt phòng";
const description = "Xác nhận thông tin khách, lựa chọn thanh toán và hoàn tất đặt phòng tại Luxury Hotel.";

export const metadata: Metadata = {
  title,
  description,
  alternates: { canonical: "/booking" },
  openGraph: {
    title,
    description,
    url: "/booking",
  },
  robots: { index: false, follow: false },
};

export default function BookingRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
