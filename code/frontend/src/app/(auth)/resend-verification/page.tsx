import type { Metadata } from "next";
import RecoveryShell from "@/components/auth/RecoveryShell";
import ResendVerificationForm from "./ResendVerificationForm";

export const metadata: Metadata = {
  title: "Gửi lại email xác thực | Luxury Hotel",
  description: "Nhận lại liên kết xác thực cho tài khoản Luxury Hotel đang chờ kích hoạt.",
};

export default function ResendVerificationPage() {
  return <RecoveryShell><ResendVerificationForm /></RecoveryShell>;
}
