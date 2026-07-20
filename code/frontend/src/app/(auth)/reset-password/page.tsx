import type { Metadata } from "next";
import { Suspense } from "react";
import RecoveryShell from "@/components/auth/RecoveryShell";
import ResetPasswordForm from "./ResetPasswordForm";

export const metadata: Metadata = {
  title: "Đặt lại mật khẩu | Luxury Hotel",
  description: "Đặt mật khẩu mới cho tài khoản Luxury Hotel.",
};

export default function ResetPasswordPage() {
  return (
    <RecoveryShell>
      <Suspense fallback={<div className="h-72 animate-pulse rounded-xl bg-[#E5E9ED]" />}>
        <ResetPasswordForm />
      </Suspense>
    </RecoveryShell>
  );
}
