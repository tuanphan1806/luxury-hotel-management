import type { Metadata } from "next";

export const metadata: Metadata = {
  alternates: { canonical: "/cancellation-policy" },
  openGraph: { url: "/cancellation-policy" },
};

export default function CancellationPolicyLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
