import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Thanh toán đặt phòng",
  robots: { index: false, follow: false },
};

export default function BookingRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
