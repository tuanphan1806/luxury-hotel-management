import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Thông tin tài khoản",
  robots: { index: false, follow: false },
};

export default function AccountRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
