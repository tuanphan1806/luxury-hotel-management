"use client";

import React, { useState, useEffect } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getApiErrorMessage } from "@/lib/api";
import Button from "@/components/UI/Button";
import ViewportModal from "@/components/UI/ViewportModal";

type UserRole = "CUSTOMER" | "STAFF" | "ADMIN";
type EditUserField = "fullName" | "username" | "email" | "phone" | "password" | "confirmPassword";

export interface EditUserFormData {
  fullName: string;
  username: string;
  email: string;
  phone: string;
  address: string;
  type: UserRole;
  password: string;
  confirmPassword: string;
}

interface UserItem {
  id: number;
  fullName: string;
  username: string;
  email: string;
  phone: string;
  address: string;
  type: UserRole;
  status: "ACTIVE" | "INACTIVE";
  imageUrl?: string;
}

interface EditGuestModalProps {
  isOpen: boolean;
  onClose: () => void;
  user: UserItem | null;
  onConfirm: (id: number, data: EditUserFormData) => Promise<void>;
}

export default function EditGuestModal({ isOpen, onClose, user, onConfirm }: EditGuestModalProps) {
  const { localize } = useLanguage();
  const [formData, setFormData] = useState({
    fullName: "",
    username: "",
    email: "",
    phone: "",
    address: "",
    type: "CUSTOMER" as UserRole,
    password: "",
    confirmPassword: "",
  });
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<EditUserField, string>>>({});

  useEffect(() => {
    if (user && isOpen) {
      setFormData({
        fullName: user.fullName || "",
        username: user.username || "",
        email: user.email || "",
        phone: user.phone || "",
        address: user.address || "",
        type: user.type || "CUSTOMER",
        password: "",
        confirmPassword: "",
      });
      setErrorMsg("");
      setFieldErrors({});
    }
  }, [user, isOpen]);

  if (!isOpen || !user) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const nextErrors: Partial<Record<EditUserField, string>> = {};
    if (!formData.fullName.trim()) nextErrors.fullName = localize("Vui lòng nhập họ và tên.", "Enter the full name.");
    if (!formData.username.trim()) nextErrors.username = localize("Vui lòng nhập tên đăng nhập.", "Enter the username.");
    if (!formData.email.trim()) nextErrors.email = localize("Vui lòng nhập email.", "Enter the email address.");
    if (!formData.phone.trim()) nextErrors.phone = localize("Vui lòng nhập số điện thoại.", "Enter the phone number.");
    if (formData.password && (formData.password.length < 8 || formData.password.length > 72)) nextErrors.password = localize("Mật khẩu mới cần từ 8 đến 72 ký tự.", "New password must contain 8–72 characters.");
    if (formData.password !== formData.confirmPassword) nextErrors.confirmPassword = localize("Hai mật khẩu chưa khớp.", "The passwords do not match.");
    setFieldErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      window.requestAnimationFrame(() => {
        document.querySelector<HTMLElement>("#edit-user-form [aria-invalid='true']")?.focus();
      });
      return;
    }

    setIsSubmitting(true);
    setErrorMsg("");
    try {
      await onConfirm(user.id, formData);
      onClose();
    } catch (err: unknown) {
      setErrorMsg(getApiErrorMessage(err, "Không thể cập nhật thông tin người dùng."));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <ViewportModal
      open={isOpen}
      onClose={onClose}
      labelledBy="edit-user-title"
      describedBy="edit-user-description"
      busy={isSubmitting}
      panelClassName="max-w-lg"
      backdropClassName="bg-[#091E30]/62"
    >
      <form id="edit-user-form" onSubmit={handleSubmit} className="flex min-h-0 flex-col">
        <header className="border-b border-[#0F2A43]/10 bg-[#F7F4EC] px-5 py-4 sm:px-6">
          <h3 id="edit-user-title" className="text-xl font-bold text-[#0F2A43]">{localize("Chỉnh sửa người dùng", "Edit user details")}</h3>
          <p id="edit-user-description" className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Cập nhật thông tin tài khoản và vai trò truy cập.", "Update account details and access role.")}</p>
        </header>

        <div className="min-h-0 space-y-4 overflow-y-auto px-5 py-5 sm:px-6">
          {errorMsg && (
            <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700">
              {errorMsg}
            </div>
          )}

          <div>
            <label htmlFor="edit-user-full-name" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
              {localize("Họ và tên *", "Full name *")}
            </label>
            <input
              id="edit-user-full-name"
              data-modal-autofocus
              type="text"
              autoComplete="name"
              required
              value={formData.fullName}
              onChange={(e) => {
                setFormData({ ...formData, fullName: e.target.value });
                setFieldErrors((current) => ({ ...current, fullName: undefined }));
              }}
              aria-invalid={Boolean(fieldErrors.fullName)}
              aria-describedby={fieldErrors.fullName ? "edit-user-full-name-error" : undefined}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
            />
            {fieldErrors.fullName && <p id="edit-user-full-name-error" className="mt-1 text-xs text-rose-700">{fieldErrors.fullName}</p>}
          </div>

          <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-4">
            <p className="mb-3 text-xs font-bold text-amber-900">{localize("Đặt lại mật khẩu (không bắt buộc)", "Reset password (optional)")}</p>
            <div className="grid gap-3 sm:grid-cols-2">
              <div>
                <label htmlFor="edit-user-password" className="mb-1 block text-xs font-semibold text-amber-900">{localize("Mật khẩu mới", "New password")}</label>
                <input id="edit-user-password" type="password" autoComplete="new-password" value={formData.password} onChange={(e) => { setFormData({ ...formData, password: e.target.value }); setFieldErrors((current) => ({ ...current, password: undefined })); }} aria-invalid={Boolean(fieldErrors.password)} aria-describedby={fieldErrors.password ? "edit-user-password-error" : undefined} className="min-h-11 w-full rounded-lg border border-amber-200 bg-white px-3 text-sm font-medium outline-none focus:border-amber-500 focus:ring-2 focus:ring-amber-500/20" />
                {fieldErrors.password && <p id="edit-user-password-error" className="mt-1 text-xs text-rose-700">{fieldErrors.password}</p>}
              </div>
              <div>
                <label htmlFor="edit-user-confirm-password" className="mb-1 block text-xs font-semibold text-amber-900">{localize("Xác nhận mật khẩu", "Confirm password")}</label>
                <input id="edit-user-confirm-password" type="password" autoComplete="new-password" value={formData.confirmPassword} onChange={(e) => { setFormData({ ...formData, confirmPassword: e.target.value }); setFieldErrors((current) => ({ ...current, confirmPassword: undefined })); }} aria-invalid={Boolean(fieldErrors.confirmPassword)} aria-describedby={fieldErrors.confirmPassword ? "edit-user-confirm-password-error" : undefined} className="min-h-11 w-full rounded-lg border border-amber-200 bg-white px-3 text-sm font-medium outline-none focus:border-amber-500 focus:ring-2 focus:ring-amber-500/20" />
                {fieldErrors.confirmPassword && <p id="edit-user-confirm-password-error" className="mt-1 text-xs text-rose-700">{fieldErrors.confirmPassword}</p>}
              </div>
            </div>
            <p className="mt-2 text-xs font-medium text-amber-800">{localize("Để trống cả hai ô nếu chỉ cập nhật thông tin.", "Leave both fields empty when only updating profile details.")}</p>
          </div>
          <div>
            <label htmlFor="edit-user-username" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
              {localize("Tên đăng nhập *", "Username *")}
            </label>
            <input
              id="edit-user-username"
              type="text"
              autoComplete="username"
              required
              value={formData.username}
              onChange={(e) => { setFormData({ ...formData, username: e.target.value }); setFieldErrors((current) => ({ ...current, username: undefined })); }}
              aria-invalid={Boolean(fieldErrors.username)}
              aria-describedby={fieldErrors.username ? "edit-user-username-error" : undefined}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
            />
            {fieldErrors.username && <p id="edit-user-username-error" className="mt-1 text-xs text-rose-700">{fieldErrors.username}</p>}
          </div>
          <div>
            <label htmlFor="edit-user-email" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
              Email *
            </label>
            <input
              id="edit-user-email"
              type="email"
              autoComplete="email"
              required
              value={formData.email}
              onChange={(e) => { setFormData({ ...formData, email: e.target.value }); setFieldErrors((current) => ({ ...current, email: undefined })); }}
              aria-invalid={Boolean(fieldErrors.email)}
              aria-describedby={fieldErrors.email ? "edit-user-email-error" : undefined}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
            />
            {fieldErrors.email && <p id="edit-user-email-error" className="mt-1 text-xs text-rose-700">{fieldErrors.email}</p>}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="edit-user-phone" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
                {localize("Số điện thoại *", "Phone *")}
              </label>
              <input
                id="edit-user-phone"
                type="tel"
                autoComplete="tel"
                required
                value={formData.phone}
                onChange={(e) => { setFormData({ ...formData, phone: e.target.value }); setFieldErrors((current) => ({ ...current, phone: undefined })); }}
                aria-invalid={Boolean(fieldErrors.phone)}
                aria-describedby={fieldErrors.phone ? "edit-user-phone-error" : undefined}
                className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
              />
              {fieldErrors.phone && <p id="edit-user-phone-error" className="mt-1 text-xs text-rose-700">{fieldErrors.phone}</p>}
            </div>
            <div>
              <label htmlFor="edit-user-role" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
                {localize("Vai trò *", "Role *")}
              </label>
              <select
                id="edit-user-role"
                value={formData.type}
                onChange={(e) => setFormData({ ...formData, type: e.target.value as UserRole })}
                className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3.5 text-sm font-semibold outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
              >
                <option value="CUSTOMER">{localize("Khách hàng", "Customer")}</option>
                <option value="STAFF">{localize("Nhân viên", "Staff")}</option>
                <option value="ADMIN">{localize("Quản trị viên", "Administrator")}</option>
              </select>
            </div>
          </div>
          <div>
            <label htmlFor="edit-user-address" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
              {localize("Địa chỉ", "Address")}
            </label>
            <input
              id="edit-user-address"
              type="text"
              autoComplete="street-address"
              value={formData.address}
              onChange={(e) => setFormData({ ...formData, address: e.target.value })}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
            />
          </div>
        </div>

        <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
          <Button variant="secondary" disabled={isSubmitting} onClick={onClose}>{localize("Hủy", "Cancel")}</Button>
          <Button type="submit" loading={isSubmitting} loadingLabel={localize("Đang lưu...", "Saving...")}>{localize("Lưu thay đổi", "Save changes")}</Button>
        </footer>
      </form>
    </ViewportModal>
  );
}
