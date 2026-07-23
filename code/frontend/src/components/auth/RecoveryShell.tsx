import AuthSplitShell from "@/components/auth/AuthSplitShell";

export default function RecoveryShell({ children }: { children: React.ReactNode }) {
  return <AuthSplitShell mode="recovery">{children}</AuthSplitShell>;
}
