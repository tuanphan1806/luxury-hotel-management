import type { Metadata } from "next";
import RecoveryShell from "@/components/auth/RecoveryShell";
import ForgotPasswordForm from "./ForgotPasswordForm";

export const metadata: Metadata = {
  title: "Quên mật khẩu | Luxury Hotel",
  description: "Nhận liên kết đặt lại mật khẩu cho tài khoản Luxury Hotel.",
};

export default function ForgotPasswordPage() {
  return <RecoveryShell><ForgotPasswordForm /></RecoveryShell>;
}
