import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Quản trị khách sạn",
  robots: {
    index: false,
    follow: false,
    nocache: true,
  },
};

export default function DashboardRouteLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
