import { Metadata } from 'next';
import AuthSplitShell from '@/components/auth/AuthSplitShell';
import LoginForm from './LoginForm';

export const metadata: Metadata = {
  title: 'Đăng nhập',
  description: 'Đăng nhập tài khoản Luxury Hotel để quản lý đơn đặt phòng và hóa đơn.',
};

export default function LoginPage() {
  return (
    <AuthSplitShell mode="login">
        <LoginForm />
    </AuthSplitShell>
  );
}
