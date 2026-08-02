import type { Metadata } from "next";

export const metadata: Metadata = {
  alternates: { canonical: "/terms" },
  openGraph: { url: "/terms" },
};

export default function TermsLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
