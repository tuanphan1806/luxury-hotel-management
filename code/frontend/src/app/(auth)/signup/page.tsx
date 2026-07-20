import { Metadata } from 'next';
import Link from 'next/link';
import AuthBrandPanel from '@/components/auth/AuthBrandPanel';
import SignupForm from './SignupForm';

export const metadata: Metadata = {
  title: 'Đăng ký',
  description: 'Tạo tài khoản khách hàng Luxury Hotel để đặt phòng và theo dõi kỳ nghỉ.',
};

export default function SignupPage() {
  return (
    <div className="flex min-h-screen w-full bg-[#F1F0EA]">
      <AuthBrandPanel mode="signup" />

      {/* ───── Right Form Panel (Đã chuẩn hóa padding px-6 py-12 lg:px-16) ───── */}
      <div className="flex flex-1 items-center justify-center overflow-y-auto bg-[#F1F0EA] px-5 py-10 sm:px-8 lg:px-14 xl:px-20">
        <div className="w-full max-w-md">
          <Link href="/" className="mb-8 inline-flex items-center gap-2 text-sm font-bold text-[#0F2A43] lg:hidden">← Luxury Hotel</Link>
          <SignupForm />
        </div>
      </div>
    </div>
  );
}
