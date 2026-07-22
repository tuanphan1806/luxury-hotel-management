import type { Metadata } from "next";

const title = "Tiện nghi khách sạn";
const description = "Tìm hiểu các tiện nghi và không gian phục vụ kỳ nghỉ tại Luxury Hotel.";

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
