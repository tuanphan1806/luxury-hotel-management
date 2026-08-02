import type { Metadata } from "next";

export const metadata: Metadata = {
  alternates: { canonical: "/booking-policy" },
  openGraph: { url: "/booking-policy" },
};

export default function BookingPolicyLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return children;
}
