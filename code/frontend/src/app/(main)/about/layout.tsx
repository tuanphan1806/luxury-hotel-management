import type { Metadata } from "next";

const title = "Về chúng tôi";
const description = "Tìm hiểu cách Luxury Hotel chuẩn bị hành trình lưu trú, đặt phòng trực tiếp và hỗ trợ khách hàng.";

export const metadata: Metadata = {
  title,
  description,
  alternates: { canonical: "/about" },
  openGraph: { title, description, url: "/about" },
};

export default function AboutRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
