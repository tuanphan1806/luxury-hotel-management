import type { Metadata } from "next";

export const metadata: Metadata = {
  alternates: { canonical: "/data-deletion" },
  openGraph: { url: "/data-deletion" },
};

export default function DataDeletionLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
