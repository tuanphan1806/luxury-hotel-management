import { Metadata } from 'next';
import Link from 'next/link';
import AuthBrandPanel from '@/components/auth/AuthBrandPanel';
import LoginForm from './LoginForm';

export const metadata: Metadata = {
  title: 'Đăng nhập',
  description: 'Đăng nhập tài khoản Luxury Hotel để quản lý đơn đặt phòng và hóa đơn.',
};

export default function LoginPage() {
  return (
    <>
      <AuthBrandPanel mode="login" />

      {/* ───── Right Form Panel ───── */}
      <div className="flex flex-1 items-center justify-center bg-[#F1F0EA] px-5 py-10 sm:px-8 lg:px-14 xl:px-20">
        <div className="w-full max-w-md">
          <Link href="/" className="mb-10 inline-flex items-center gap-2 text-sm font-bold text-[#0F2A43] lg:hidden">← Luxury Hotel</Link>
          <LoginForm />
        </div>
      </div>
    </>
  );
}
