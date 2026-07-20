import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Đặt phòng",
  robots: { index: false, follow: false },
};

export default function ReservationRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
