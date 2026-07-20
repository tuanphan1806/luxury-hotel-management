import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Đặt phòng của tôi",
  robots: { index: false, follow: false },
};

export default function MyBookingsRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
