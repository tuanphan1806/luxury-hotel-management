"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useAccessibility } from "@/components/accessibility/AccessibilityProvider";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import ImageUploadField from "@/components/UI/ImageUploadField";
import Toast from "@/components/UI/Toast";
import { apiClient, getApiErrorMessage } from "@/lib/api";

type AccountSettingsVariant = "guest" | "operations";
export type AccountSettingsView = "profile" | "security" | "accessibility";

interface AccountSettingsProps {
  variant?: AccountSettingsVariant;
  view?: AccountSettingsView;
}

interface UserProfile {
  id: number;
  fullName: string;
  username: string;
  email: string;
  phone: string;
  address: string;
  imageUrl: string;
  type: "CUSTOMER" | "STAFF" | "ADMIN";
}

const emptyProfile: UserProfile = {
  id: 0,
  fullName: "",
  username: "",
  email: "",
  phone: "",
  address: "",
  imageUrl: "",
  type: "CUSTOMER",
};

const normalizeProfile = (value: Partial<UserProfile> | null | undefined): UserProfile => ({
  ...emptyProfile,
  ...value,
  type: value?.type === "ADMIN" || value?.type === "STAFF" ? value.type : "CUSTOMER",
});

const cacheProfile = (nextProfile: UserProfile) => {
  localStorage.setItem("user", JSON.stringify({
    fullName: nextProfile.fullName,
    username: nextProfile.username,
    email: nextProfile.email,
    phone: nextProfile.phone,
    imageUrl: nextProfile.imageUrl,
    type: nextProfile.type,
    role: nextProfile.type,
  }));
  window.dispatchEvent(new Event("storage"));
};

export default function AccountSettings({ variant = "operations", view = "profile" }: AccountSettingsProps) {
  const { localize } = useLanguage();
  const { preferences, updatePreferences, resetPreferences } = useAccessibility();
  const [profile, setProfile] = useState<UserProfile>(emptyProfile);
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [isSaving, setIsSaving] = useState(false);
  const [isChangingPassword, setIsChangingPassword] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const showToast = (message: string, type: "success" | "error" | "info") => setToast({ message, type });

  const loadProfile = useCallback(async () => {
    setIsLoading(true);
    setLoadError("");
    try {
      const response = await apiClient.get("/api/user/me");
      const currentProfile = normalizeProfile(response.data?.data);
      setProfile(currentProfile);
      cacheProfile(currentProfile);
    } catch (error: unknown) {
      setLoadError(getApiErrorMessage(
        error,
        localize("Không thể tải thông tin tài khoản. Vui lòng đăng nhập lại.", "Unable to load your account. Please sign in again."),
      ));
    } finally {
      setIsLoading(false);
    }
  }, [localize]);

  useEffect(() => {
    void loadProfile();
  }, [loadProfile]);

  const saveProfile = async (event: React.FormEvent) => {
    event.preventDefault();
    if (isUploading) {
      showToast(localize("Vui lòng đợi ảnh tải xong trước khi lưu.", "Please wait for the image upload to finish before saving."), "info");
      return;
    }
    if (!profile.fullName.trim() || !profile.phone.trim()) {
      showToast(localize("Họ tên và số điện thoại là bắt buộc.", "Full name and phone number are required."), "error");
      return;
    }

    setIsSaving(true);
    try {
      const nextProfile = { ...profile, fullName: profile.fullName.trim(), phone: profile.phone.trim(), address: profile.address.trim() };
      await apiClient.put(`/api/user/${profile.id}`, nextProfile);
      setProfile(nextProfile);
      cacheProfile(nextProfile);
      showToast(localize("Đã cập nhật thông tin tài khoản.", "Account information updated."), "success");
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, localize("Không thể cập nhật tài khoản.", "Unable to update account.")), "error");
    } finally {
      setIsSaving(false);
    }
  };

  const changePassword = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!oldPassword || !newPassword || !confirmPassword) {
      showToast(localize("Vui lòng nhập đủ ba trường mật khẩu.", "Please complete all three password fields."), "error");
      return;
    }
    if (newPassword.length < 8 || !/[A-Za-zÀ-ỹ]/.test(newPassword) || !/\d/.test(newPassword)) {
      showToast(localize("Mật khẩu mới cần ít nhất 8 ký tự, có chữ và số.", "Use at least 8 characters with both letters and numbers."), "error");
      return;
    }
    if (newPassword !== confirmPassword) {
      showToast(localize("Xác nhận mật khẩu không khớp.", "Password confirmation does not match."), "error");
      return;
    }

    setIsChangingPassword(true);
    try {
      await apiClient.patch("/api/user/change-password", {
        id: profile.id,
        currentPassword: oldPassword,
        password: newPassword,
        confirmPassword,
      });
      setOldPassword("");
      setNewPassword("");
      setConfirmPassword("");
      showToast(localize("Đã đổi mật khẩu.", "Password updated."), "success");
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, localize("Mật khẩu hiện tại không đúng.", "The current password is incorrect.")), "error");
    } finally {
      setIsChangingPassword(false);
    }
  };

  const inputClass = "min-h-12 w-full rounded-lg border border-[#0F2A43]/18 bg-white px-4 py-3 text-sm font-medium text-[#0F2A43] outline-none transition placeholder:text-[#66727C]/70 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20";
  const labelClass = "mb-2 block text-xs font-bold text-[#0F2A43]";
  const isGuest = variant === "guest";
  const profileInitial = profile.fullName.trim().charAt(0) || profile.username.trim().charAt(0) || "U";
  const completedProfileFields = [profile.fullName, profile.email, profile.phone, profile.address, profile.imageUrl].filter((value) => value?.trim()).length;
  const profileCompletion = Math.round((completedProfileFields / 5) * 100);
  const passwordChecks = {
    length: newPassword.length >= 8,
    letter: /[A-Za-zÀ-ỹ]/.test(newPassword),
    number: /\d/.test(newPassword),
    match: Boolean(confirmPassword) && newPassword === confirmPassword,
  };

  if (isLoading) {
    return (
      <div className={`mx-auto w-full ${isGuest ? "max-w-[1180px] px-4 pb-20 pt-32 md:px-8" : "max-w-[1320px] p-6 md:p-10"}`}>
        <div className="animate-pulse space-y-5" aria-label={localize("Đang tải tài khoản", "Loading account")}><div className="h-28 rounded-xl skeleton-surface" /><div className="h-72 rounded-xl skeleton-surface" /></div>
      </div>
    );
  }

  if (loadError) {
    return (
      <div className={`mx-auto w-full ${isGuest ? "max-w-3xl px-4 pb-20 pt-32 md:px-8" : "max-w-3xl p-6 md:p-10"}`}>
        <section className="rounded-xl border border-rose-200 bg-white p-7 text-center">
          <h1 className="font-serif text-2xl font-bold text-[#0F2A43]">{localize("Không thể mở tài khoản", "Unable to open account")}</h1>
          <p className="mt-2 text-sm text-[#66727C]">{loadError}</p>
          <div className="mt-5 flex justify-center gap-3"><button type="button" onClick={() => void loadProfile()} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white">{localize("Thử lại", "Try again")}</button><Link href="/login" className="inline-flex min-h-11 items-center rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">{localize("Đăng nhập", "Sign in")}</Link></div>
        </section>
      </div>
    );
  }

  const guestNavigation = (
    <nav aria-label={localize("Cài đặt tài khoản", "Account settings")} className="grid gap-2 rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6] p-2 sm:grid-cols-3">
      {[
        ["/account", "profile", localize("Hồ sơ cá nhân", "Personal profile")],
        ["/account/security", "security", localize("Bảo mật & mật khẩu", "Security & password")],
        ["/account/accessibility", "accessibility", localize("Màn hình & trợ năng", "Display & accessibility")],
      ].map(([href, itemView, label]) => (
        <Link key={href} href={href} aria-current={view === itemView ? "page" : undefined} className={`flex min-h-12 items-center justify-center rounded-lg px-4 text-center text-sm font-bold transition ${view === itemView ? "bg-[#0F2A43] text-white shadow-[0_8px_24px_rgba(15,42,67,0.16)]" : "text-[#66727C] hover:bg-[#F0EADF] hover:text-[#0F2A43]"}`}>{label}</Link>
      ))}
    </nav>
  );

  const pageHeader = isGuest && view === "profile" ? (
    <header className="relative overflow-hidden rounded-[1.5rem] bg-[#0F2A43] px-6 py-7 text-white shadow-[0_22px_60px_rgba(15,42,67,0.16)] md:px-8 md:py-8">
      <div aria-hidden="true" className="absolute -right-16 -top-24 h-64 w-64 rounded-full border border-[#B8944F]/18" />
      <div className="relative grid gap-7 lg:grid-cols-[1fr_0.42fr] lg:items-center">
        <div className="flex min-w-0 items-center gap-5">
          <span className="flex h-20 w-20 shrink-0 items-center justify-center rounded-full border border-[#B8944F]/70 font-serif text-3xl font-bold uppercase text-[#F1F0EA]">{profileInitial}</span>
          <div className="min-w-0"><p className="text-[10px] font-bold uppercase tracking-[0.24em] text-[#D8C398]">{localize("Hồ sơ cá nhân", "Personal profile")}</p><div className="mt-2 flex flex-wrap items-center gap-3"><h1 className="truncate font-serif text-3xl font-bold md:text-4xl">{profile.fullName || localize("Tài khoản", "Account")}</h1><span className="rounded-md border border-[#B8944F]/50 px-2.5 py-1 text-[9px] font-bold uppercase tracking-[0.14em] text-[#D8C398]">{localize("Khách hàng", "Guest")}</span></div><p className="mt-2 truncate text-sm text-white/68">{profile.email || `@${profile.username}`}</p></div>
        </div>
        <div className="border-t border-white/12 pt-5 lg:border-l lg:border-t-0 lg:pl-7 lg:pt-0"><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#D8C398]">{localize("Dùng cho kỳ lưu trú", "For your stay")}</p><p className="mt-2 text-xs font-medium leading-5 text-white/66">{localize("Thông tin chính xác giúp xác nhận đặt phòng và liên hệ hỗ trợ thuận tiện hơn.", "Accurate details make booking confirmation and support easier.")}</p></div>
      </div>
    </header>
  ) : (
    <header className="rounded-xl border-l-2 border-[#0F2A43] bg-[#FBFAF6] px-5 py-7 md:px-7">
      <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Tài khoản", "Account")}</p>
      <h1 className="mt-2 font-serif text-4xl font-bold text-[#0F2A43] md:text-5xl">{view === "security" ? localize("Bảo mật & mật khẩu", "Security & password") : view === "accessibility" ? localize("Màn hình & trợ năng", "Display & accessibility") : localize("Thông tin tài khoản", "Account settings")}</h1>
      <p className="mt-3 max-w-3xl text-sm leading-6 text-[#66727C]">{view === "security" ? localize("Quản lý đăng nhập, đổi mật khẩu và giữ tài khoản an toàn.", "Manage sign-in, update your password, and keep your account secure.") : view === "accessibility" ? localize("Điều chỉnh cách hiển thị để website dễ đọc và dễ thao tác hơn.", "Adjust the display to make the website easier to read and use.") : localize("Cập nhật thông tin liên hệ và ảnh đại diện của bạn.", "Update your contact details and profile photo.")}</p>
    </header>
  );

  const profileForm = (
    <form id="profile" onSubmit={saveProfile} className="overflow-hidden rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6]">
      <div className="border-b border-[#0F2A43]/10 p-6 md:p-8">
        <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Thông tin tài khoản", "Account information")}</p>
        <h2 className="mt-2 font-serif text-3xl font-bold text-[#0F2A43]">{localize("Chi tiết cá nhân", "Personal details")}</h2>
        <p className="mt-2 text-sm text-[#66727C]">{localize("Thông tin này được dùng khi đặt phòng, nhận phòng và liên hệ hỗ trợ.", "These details are used for bookings, check-in, and support.")}</p>
        <ImageUploadField id="account-avatar-upload" folder="AVATAR" value={profile.imageUrl} label={localize("Ảnh đại diện", "Profile photo")} alt={localize(`Ảnh đại diện của ${profile.fullName || "tài khoản"}`, `${profile.fullName || "Account"} profile photo`)} description={localize("Ảnh vuông JPEG, PNG hoặc WebP · tối đa 5 MB.", "Square JPEG, PNG or WebP · up to 5 MB.")} tone="light" aspect="square" className="mt-5 max-w-xl" onUploadingChange={setIsUploading} onUploaded={(image) => setProfile((current) => ({ ...current, imageUrl: image.url }))} />
      </div>
      <div className="grid gap-5 p-6 md:grid-cols-2 md:p-8">
        <label><span className={labelClass}>{localize("Họ và tên *", "Full name *")}</span><input className={inputClass} value={profile.fullName} onChange={(event) => setProfile({ ...profile, fullName: event.target.value })} /></label>
        <label><span className={labelClass}>{localize("Số điện thoại *", "Phone number *")}</span><input type="tel" className={inputClass} value={profile.phone} onChange={(event) => setProfile({ ...profile, phone: event.target.value })} /></label>
        <label><span className={labelClass}>{localize("Tên đăng nhập", "Username")}</span><input className={`${inputClass} cursor-not-allowed bg-[#F1F0EA] text-[#66727C]`} disabled value={profile.username} /></label>
        <label><span className={labelClass}>Email</span><input className={`${inputClass} cursor-not-allowed bg-[#F1F0EA] text-[#66727C]`} disabled value={profile.email} /></label>
        <label className="md:col-span-2"><span className={labelClass}>{localize("Địa chỉ", "Address")}</span><input className={inputClass} value={profile.address} onChange={(event) => setProfile({ ...profile, address: event.target.value })} placeholder={localize("Nhập địa chỉ liên hệ", "Enter contact address")} /></label>
        <div className="flex flex-wrap items-center justify-between gap-4 border-t border-[#0F2A43]/10 pt-5 md:col-span-2"><p className="text-xs leading-5 text-[#66727C]">{localize("Email và tên đăng nhập cần lễ tân hỗ trợ nếu muốn thay đổi.", "Contact support to change your email or username.")}</p><button disabled={isSaving || isUploading || !profile.id} className="min-h-11 rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-50">{isSaving ? localize("Đang lưu...", "Saving...") : localize("Lưu thay đổi", "Save changes")}</button></div>
      </div>
    </form>
  );

  const securityForm = (
    <form id="security" onSubmit={changePassword} className="rounded-xl border border-[#0F2A43]/18 bg-white p-6 md:p-8">
      <div className="flex items-start gap-4 border-b border-[#0F2A43]/10 pb-5"><span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg bg-[#0F2A43] text-white"><svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8"><rect x="5" y="10" width="14" height="10" rx="2" /><path d="M8 10V7a4 4 0 0 1 8 0v3" /></svg></span><div><h2 className="text-xl font-bold text-[#0F2A43]">{localize("Đổi mật khẩu", "Change password")}</h2><p className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Hoàn thành ba bước dưới đây để cập nhật mật khẩu đăng nhập.", "Complete the three fields below to update your sign-in password.")}</p></div></div>
      <div className="mt-6 grid gap-5">
        <label><span className={labelClass}>{localize("Mật khẩu hiện tại *", "Current password *")}</span><input type="password" autoComplete="current-password" className={inputClass} value={oldPassword} onChange={(event) => setOldPassword(event.target.value)} placeholder={localize("Nhập mật khẩu đang dùng", "Enter your current password")} /></label>
        <div className="rounded-xl border border-[#0F2A43]/10 bg-[#F4F7F5] p-4 md:p-5">
          <label><span className={labelClass}>{localize("Mật khẩu mới *", "New password *")}</span><input type="password" autoComplete="new-password" className={inputClass} value={newPassword} onChange={(event) => setNewPassword(event.target.value)} placeholder={localize("Tối thiểu 8 ký tự, có chữ và số", "At least 8 characters with letters and numbers")} /></label>
          <label className="mt-4 block"><span className={labelClass}>{localize("Nhập lại mật khẩu mới *", "Confirm new password *")}</span><input type="password" autoComplete="new-password" className={inputClass} value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} /></label>
          <ul className="mt-4 grid gap-2 text-xs sm:grid-cols-2">
            {[[passwordChecks.length, localize("Ít nhất 8 ký tự", "At least 8 characters")], [passwordChecks.letter, localize("Có chữ cái", "Contains a letter")], [passwordChecks.number, localize("Có chữ số", "Contains a number")], [passwordChecks.match, localize("Hai mật khẩu khớp nhau", "Passwords match")]].map(([passed, label]) => <li key={String(label)} className={passed ? "font-semibold text-emerald-700" : "text-[#66727C]"}><span aria-hidden="true">{passed ? "✓" : "○"}</span> {label}</li>)}
          </ul>
        </div>
      </div>
      <div className="mt-6 flex flex-wrap items-center justify-between gap-4 border-t border-[#0F2A43]/10 pt-5"><p className="text-xs text-[#66727C]">{localize("Sau khi đổi, phiên đăng nhập hiện tại vẫn được giữ.", "Your current session remains active after the update.")}</p><button disabled={isChangingPassword || !profile.id} className="min-h-11 rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-50">{isChangingPassword ? localize("Đang cập nhật...", "Updating...") : localize("Cập nhật mật khẩu", "Update password")}</button></div>
    </form>
  );

  return (
    <div className={`mx-auto w-full space-y-6 ${isGuest ? "max-w-[1180px] px-4 pb-24 pt-32 md:px-8 lg:pt-36" : "max-w-[1320px] p-6 md:p-10"}`}>
      {pageHeader}
      {isGuest && guestNavigation}

      {(!isGuest || view === "profile") && (
        <section className={`grid gap-6 ${isGuest ? "xl:grid-cols-[minmax(0,1.55fr)_minmax(300px,0.75fr)]" : "xl:grid-cols-[minmax(0,1.4fr)_minmax(320px,0.8fr)]"}`}>
          {profileForm}
          <div className="space-y-5">
            <section className="rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6] p-6" aria-labelledby="profile-completion-title"><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Mức độ hoàn thiện", "Profile completion")}</p><div className="mt-4 flex items-end justify-between gap-4"><h2 id="profile-completion-title" className="text-3xl font-bold tabular-nums text-[#0F2A43]">{profileCompletion}%</h2><span className="text-xs font-semibold text-[#66727C]">{completedProfileFields}/5 {localize("mục", "items")}</span></div><div className="mt-4 h-1.5 overflow-hidden rounded-full bg-[#E5E9ED]" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={profileCompletion}><div className="h-full rounded-full bg-[#B8944F] transition-[width] duration-300" style={{ width: `${profileCompletion}%` }} /></div><p className="mt-3 text-xs leading-5 text-[#66727C]">{profileCompletion === 100 ? localize("Hồ sơ đã có đủ thông tin cần thiết.", "Your profile includes all essential details.") : localize("Bổ sung ảnh đại diện và địa chỉ để hoàn thiện hồ sơ.", "Add a profile photo and address to complete your profile.")}</p></section>
            <nav aria-label={localize("Quản lý tài khoản", "Account management")} className="rounded-xl border border-[#0F2A43]/12 bg-[#FBFAF6] p-5"><h2 className="font-serif text-2xl font-bold text-[#0F2A43]">{localize("Quản lý tài khoản", "Account management")}</h2><div className="mt-4 divide-y divide-[#0F2A43]/10 border-y border-[#0F2A43]/10"><Link href="/my-bookings" className="flex min-h-12 items-center justify-between gap-3 text-sm font-semibold text-[#0F2A43] hover:text-[#80632F]"><span>{localize("Lịch sử đặt phòng", "Booking history")}</span><span aria-hidden="true">→</span></Link><Link href="/account/security" className="flex min-h-12 items-center justify-between gap-3 text-sm font-semibold text-[#0F2A43] hover:text-[#80632F]"><span>{localize("Bảo mật tài khoản", "Account security")}</span><span aria-hidden="true">→</span></Link><Link href="/account/accessibility" className="flex min-h-12 items-center justify-between gap-3 text-sm font-semibold text-[#0F2A43] hover:text-[#80632F]"><span>{localize("Màn hình & trợ năng", "Display & accessibility")}</span><span aria-hidden="true">→</span></Link><Link href="/support" className="flex min-h-12 items-center justify-between gap-3 text-sm font-semibold text-[#0F2A43] hover:text-[#80632F]"><span>{localize("Trung tâm hỗ trợ", "Support center")}</span><span aria-hidden="true">→</span></Link></div></nav>
            <aside className="rounded-xl border border-[#B8944F]/24 bg-[#F0EADF] p-5"><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Quyền riêng tư", "Privacy")}</p><p className="mt-2 text-xs leading-5 text-[#66727C]">{localize("Thông tin liên hệ chỉ được dùng cho tài khoản, đặt phòng và hỗ trợ lưu trú.", "Contact details are used only for your account, bookings, and stay support.")}</p><Link href="/privacy" className="mt-3 inline-flex min-h-10 items-center text-xs font-bold text-[#0F2A43] hover:underline">{localize("Xem chính sách bảo mật", "Read privacy policy")}</Link></aside>
          </div>
        </section>
      )}

      {(!isGuest || view === "security") && (
        <>
          {isGuest && <section className="grid gap-3 md:grid-cols-3"><div className="rounded-xl border border-emerald-700/20 bg-emerald-50 p-4"><p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#66727C]">{localize("Trạng thái", "Status")}</p><p className="mt-1 text-sm font-bold text-emerald-800">{localize("Được bảo vệ", "Protected")}</p></div><div className="rounded-xl border border-[#0F2A43]/16 bg-[#FBFAF6] p-4"><p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#66727C]">{localize("Yêu cầu", "Requirement")}</p><p className="mt-1 text-sm font-bold text-[#0F2A43]">{localize("Tối thiểu 8 ký tự", "At least 8 characters")}</p></div><div className="rounded-xl border border-emerald-700/20 bg-emerald-50 p-4"><p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#66727C]">{localize("Phiên hiện tại", "Current session")}</p><p className="mt-1 text-sm font-bold text-emerald-800">{localize("Vẫn giữ sau đổi", "Remains active")}</p></div></section>}
          <section className={`grid gap-5 ${isGuest ? "lg:grid-cols-[minmax(0,1fr)_18rem]" : "xl:grid-cols-[minmax(0,1fr)_20rem]"}`}>
            {securityForm}
            <div className="space-y-5"><aside className="rounded-xl bg-[#0F2A43] p-6 text-white"><h2 className="text-lg font-bold">{localize("Mẹo bảo mật", "Security tips")}</h2><div className="mt-4 space-y-3 text-xs leading-5 text-white/76"><p className="rounded-lg border border-white/12 p-3"><strong className="block text-white">{localize("Mật khẩu riêng biệt", "Use a unique password")}</strong>{localize("Không dùng chung với email hoặc mạng xã hội.", "Do not reuse your email or social password.")}</p><p className="rounded-lg border border-white/12 p-3"><strong className="block text-white">{localize("Đổi khi nghi ngờ", "Change it when needed")}</strong>{localize("Cập nhật ngay nếu bạn thấy hoạt động bất thường.", "Update it if you notice unusual activity.")}</p><p className="rounded-lg border border-white/12 p-3"><strong className="block text-white">{localize("Đăng xuất thiết bị lạ", "Sign out on shared devices")}</strong>{localize("Không lưu mật khẩu trên máy dùng chung.", "Do not save passwords on shared computers.")}</p></div></aside><nav aria-label={localize("Liên kết tài khoản", "Account links")} className="rounded-xl border border-[#0F2A43]/16 bg-[#FBFAF6] p-5"><h2 className="text-sm font-bold text-[#0F2A43]">{localize("Liên kết tài khoản", "Account links")}</h2><div className="mt-3 grid gap-2"><Link href="/account" className="flex min-h-11 items-center justify-between rounded-lg border border-[#0F2A43]/14 px-3 text-xs font-bold text-[#0F2A43]">{localize("Hồ sơ cá nhân", "Personal profile")}<span>→</span></Link><Link href="/account/accessibility" className="flex min-h-11 items-center justify-between rounded-lg border border-[#0F2A43]/14 px-3 text-xs font-bold text-[#0F2A43]">{localize("Màn hình & trợ năng", "Display & accessibility")}<span>→</span></Link></div><p className="mt-4 text-[11px] text-[#66727C]">{localize("Cần hỗ trợ đăng nhập?", "Need sign-in help?")} <Link href="/support" className="font-bold text-[#80632F] hover:underline">{localize("Trung tâm hỗ trợ", "Support center")}</Link></p></nav></div>
          </section>
        </>
      )}

      {isGuest && view === "accessibility" && (
        <section className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_21rem]">
          <div className="space-y-5 rounded-xl border border-[#0F2A43]/14 bg-[#FBFAF6] p-6 md:p-8">
            <div><h2 className="font-serif text-3xl font-bold text-[#0F2A43]">{localize("Thiết lập hiển thị", "Display preferences")}</h2><p className="mt-2 text-sm leading-6 text-[#66727C]">{localize("Các lựa chọn được lưu trên trình duyệt này và áp dụng ngay cho toàn bộ website.", "Preferences are stored in this browser and apply to the entire website immediately.")}</p></div>
            <fieldset className="border-t border-[#0F2A43]/10 pt-5"><legend className="text-sm font-bold text-[#0F2A43]">{localize("Cỡ chữ", "Text size")}</legend><div className="mt-3 grid gap-3 sm:grid-cols-2">{(["standard", "large"] as const).map((size) => <button key={size} type="button" aria-pressed={preferences.textSize === size} onClick={() => updatePreferences({ textSize: size })} className={`min-h-20 rounded-xl border p-4 text-left transition ${preferences.textSize === size ? "border-[#B8944F] bg-[#F0EADF] text-[#0F2A43]" : "border-[#0F2A43]/12 bg-white text-[#66727C] hover:border-[#B8944F]/60"}`}><span className={size === "large" ? "text-xl font-bold" : "text-base font-bold"}>{size === "large" ? localize("Chữ lớn", "Large") : localize("Tiêu chuẩn", "Standard")}</span><span className="mt-1 block text-xs">{size === "large" ? localize("Tăng cỡ chữ toàn trang", "Increase text across the site") : localize("Cỡ chữ mặc định", "Default text size")}</span></button>)}</div></fieldset>
            <div className="grid gap-3 border-t border-[#0F2A43]/10 pt-5">
              {[["highContrast", preferences.highContrast, localize("Tăng độ tương phản", "Higher contrast"), localize("Làm đậm chữ phụ và đường viền.", "Darken secondary text and borders.")], ["reduceMotion", preferences.reduceMotion, localize("Giảm chuyển động", "Reduce motion"), localize("Tắt hiệu ứng chuyển trang và chuyển động trang trí.", "Disable page transitions and decorative motion.")], ["underlineLinks", preferences.underlineLinks, localize("Gạch chân liên kết", "Underline links"), localize("Giúp nhận biết liên kết ngoài màu sắc.", "Identify links without relying only on color.")]].map(([key, enabled, label, description]) => <button key={String(key)} type="button" role="switch" aria-checked={Boolean(enabled)} onClick={() => updatePreferences({ [String(key)]: !enabled })} className="flex min-h-16 items-center justify-between gap-5 rounded-xl border border-[#0F2A43]/12 bg-white p-4 text-left transition hover:border-[#B8944F]/60"><span><strong className="block text-sm text-[#0F2A43]">{label}</strong><span className="mt-1 block text-xs leading-5 text-[#66727C]">{description}</span></span><span aria-hidden="true" className={`relative h-7 w-12 shrink-0 rounded-full transition ${enabled ? "bg-[#0F2A43]" : "bg-[#D8DDE1]"}`}><span className={`absolute top-1 h-5 w-5 rounded-full bg-white shadow-sm transition-transform ${enabled ? "translate-x-6" : "translate-x-1"}`} /></span></button>)}
            </div>
            <div className="flex flex-wrap items-center justify-between gap-4 border-t border-[#0F2A43]/10 pt-5"><p className="text-xs text-[#66727C]">{localize("Không thay đổi dữ liệu tài khoản hoặc đơn đặt phòng.", "These settings do not change account or booking data.")}</p><button type="button" onClick={resetPreferences} className="min-h-11 rounded-lg border border-[#0F2A43]/18 bg-white px-5 text-sm font-bold text-[#0F2A43] hover:bg-[#F0EADF]">{localize("Đặt lại mặc định", "Reset defaults")}</button></div>
          </div>
          <aside className="space-y-5"><div className="rounded-xl bg-[#0F2A43] p-6 text-white"><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#D8C398]">{localize("Xem trước", "Preview")}</p><h2 className="mt-3 font-serif text-3xl font-bold">Luxury Hotel</h2><p className="mt-3 text-sm leading-7 text-white/72">{localize("Trải nghiệm đặt phòng rõ ràng, dễ đọc và phù hợp với cách bạn sử dụng thiết bị.", "A clear booking experience adapted to how you use your device.")}</p><Link href="/rooms" className="mt-5 inline-flex min-h-11 items-center rounded-lg bg-[#B8944F] px-4 text-xs font-bold text-[#0F2A43]">{localize("Xem phòng", "View rooms")}</Link></div><div className="rounded-xl border border-[#0F2A43]/14 bg-[#F0EADF] p-5"><h2 className="text-sm font-bold text-[#0F2A43]">{localize("Hỗ trợ thêm", "More help")}</h2><p className="mt-2 text-xs leading-5 text-[#66727C]">{localize("Nếu bạn vẫn khó thao tác, hãy liên hệ lễ tân và mô tả thiết bị đang dùng.", "If the site remains difficult to use, contact the front desk and describe your device.")}</p><Link href="/support" className="mt-3 inline-flex min-h-10 items-center text-xs font-bold text-[#80632F] hover:underline">{localize("Mở trung tâm hỗ trợ", "Open support center")} →</Link></div></aside>
        </section>
      )}

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
