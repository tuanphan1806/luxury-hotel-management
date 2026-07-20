import type { Metadata } from "next";

const title = "Tiện ích khách sạn";
const description = "Tìm hiểu các tiện ích và không gian phục vụ kỳ nghỉ tại Luxury Hotel.";

export const metadata: Metadata = {
  title,
  description,
  alternates: {
    canonical: "/facilities",
  },
  openGraph: {
    title,
    description,
  },
};

export default function FacilitiesRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
