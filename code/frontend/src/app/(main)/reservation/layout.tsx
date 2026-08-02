import type { Metadata } from "next";

const title = "Đặt phòng";
const description = "Chọn thời gian lưu trú, số khách và hạng phòng còn trống tại Luxury Hotel.";

export const metadata: Metadata = {
  title,
  description,
  alternates: { canonical: "/reservation" },
  openGraph: {
    title,
    description,
    url: "/reservation",
  },
  robots: { index: false, follow: false },
};

export default function ReservationRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
