import { Metadata } from 'next';
import AuthSplitShell from '@/components/auth/AuthSplitShell';
import SignupForm from './SignupForm';

export const metadata: Metadata = {
  title: 'Đăng ký',
  description: 'Tạo tài khoản khách hàng Luxury Hotel để đặt phòng và theo dõi kỳ nghỉ.',
};

export default function SignupPage() {
  return (
    <AuthSplitShell mode="signup">
        <SignupForm />
    </AuthSplitShell>
  );
}
