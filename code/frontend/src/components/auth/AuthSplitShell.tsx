import AuthBrandPanel from "@/components/auth/AuthBrandPanel";
import AuthFormPanel from "@/components/auth/AuthFormPanel";

interface AuthSplitShellProps {
  mode: "login" | "signup" | "recovery";
  children: React.ReactNode;
}

export default function AuthSplitShell({ mode, children }: AuthSplitShellProps) {
  return (
    <div className="relative isolate flex min-h-[100dvh] w-full flex-col overflow-hidden bg-[#E9ECE7] lg:flex-row">
      <div aria-hidden="true" className="absolute inset-0 bg-[url('/hotel-lobby.png')] bg-cover bg-center lg:hidden">
        <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(9,30,48,0.2)_0%,rgba(9,30,48,0.1)_36%,rgba(9,30,48,0.38)_100%)]" />
      </div>
      <AuthBrandPanel mode={mode} />
      <AuthFormPanel mode={mode}>{children}</AuthFormPanel>
    </div>
  );
}
