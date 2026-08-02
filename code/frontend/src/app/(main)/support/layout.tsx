import type { Metadata } from "next";

export const metadata: Metadata = {
  alternates: { canonical: "/support" },
  openGraph: { url: "/support" },
};

export default function SupportLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
