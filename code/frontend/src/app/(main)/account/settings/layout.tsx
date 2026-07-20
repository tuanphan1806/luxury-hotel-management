import type { Metadata } from "next";

export const metadata: Metadata = {
  title: "Cài đặt tài khoản",
  robots: { index: false, follow: false },
};

export default function AccountSettingsLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
