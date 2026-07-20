import type { Metadata } from "next";
import SupportCenter from "@/components/guest/SupportCenter";

export const metadata: Metadata = { title: "Trung tâm hỗ trợ" };

export default function SupportPage() {
  return <SupportCenter />;
}
