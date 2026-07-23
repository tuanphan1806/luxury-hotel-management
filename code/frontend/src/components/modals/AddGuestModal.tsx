"use client";

import React, { useEffect, useState } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getApiErrorMessage } from "@/lib/api";
import Button from "@/components/UI/Button";
import ViewportModal from "@/components/UI/ViewportModal";

type UserRole = "CUSTOMER" | "STAFF" | "ADMIN";
type AddUserField = "fullName" | "username" | "email" | "phone" | "password";

export interface AddUserFormData {
  fullName: string;
  username: string;
  email: string;
  phone: string;
  address: string;
  type: UserRole;
  password: string;
}

interface AddGuestModalProps {
  isOpen: boolean;
  onClose: () => void;
  onConfirm: (data: AddUserFormData) => Promise<void>;
}

export default function AddGuestModal({ isOpen, onClose, onConfirm }: AddGuestModalProps) {
  const { localize } = useLanguage();
  const [formData, setFormData] = useState({
    fullName: "",
    username: "",
    email: "",
    phone: "",
    address: "",
    type: "CUSTOMER" as UserRole,
    password: "",
  });
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<AddUserField, string>>>({});

  useEffect(() => {
    if (!isOpen) return;
    setErrorMsg("");
    setFieldErrors({});
  }, [isOpen]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const nextErrors: Partial<Record<AddUserField, string>> = {};
    if (!formData.fullName.trim()) nextErrors.fullName = localize("Vui lòng nhập họ và tên.", "Enter the full name.");
    if (!formData.username.trim()) nextErrors.username = localize("Vui lòng nhập tên đăng nhập.", "Enter the username.");
    if (!formData.email.trim()) nextErrors.email = localize("Vui lòng nhập email.", "Enter the email address.");
    if (!formData.phone.trim()) nextErrors.phone = localize("Vui lòng nhập số điện thoại.", "Enter the phone number.");
    if (formData.password.length < 8 || formData.password.length > 72) nextErrors.password = localize("Mật khẩu cần từ 8 đến 72 ký tự.", "Password must contain 8–72 characters.");
    setFieldErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      window.requestAnimationFrame(() => {
        document.querySelector<HTMLElement>("#add-user-form [aria-invalid='true']")?.focus();
      });
      return;
    }

    setIsSubmitting(true);
    setErrorMsg("");
    try {
      await onConfirm(formData);
      // Reset form
      setFormData({
        fullName: "",
        username: "",
        email: "",
        phone: "",
        address: "",
        type: "CUSTOMER",
        password: "",
      });
      setFieldErrors({});
      onClose();
    } catch (err: unknown) {
      setErrorMsg(getApiErrorMessage(err, "Không thể tạo người dùng. Vui lòng kiểm tra lại."));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <ViewportModal
      open={isOpen}
      onClose={onClose}
      labelledBy="add-user-title"
      describedBy="add-user-description"
      busy={isSubmitting}
      panelClassName="max-w-lg"
      backdropClassName="bg-[#091E30]/62"
    >
      <form id="add-user-form" onSubmit={handleSubmit} className="flex min-h-0 flex-col">
        <header className="border-b border-[#0F2A43]/10 bg-[#F7F4EC] px-5 py-4 sm:px-6">
          <h3 id="add-user-title" className="text-xl font-bold text-[#0F2A43]">{localize("Thêm người dùng", "Add new user")}</h3>
          <p id="add-user-description" className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Tạo tài khoản và chọn đúng vai trò truy cập hệ thống.", "Create an account and assign the correct system role.")}</p>
        </header>

        <div className="min-h-0 space-y-4 overflow-y-auto px-5 py-5 sm:px-6">
          {errorMsg && (
            <div role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700">
              {errorMsg}
            </div>
          )}

          <div>
            <label htmlFor="add-user-full-name" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
              {localize("Họ và tên *", "Full name *")}
            </label>
            <input
              id="add-user-full-name"
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
              aria-describedby={fieldErrors.fullName ? "add-user-full-name-error" : undefined}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
              placeholder={localize("Ví dụ: Nguyễn Văn An", "For example: Nguyen Van An")}
            />
            {fieldErrors.fullName && <p id="add-user-full-name-error" className="mt-1 text-xs text-rose-700">{fieldErrors.fullName}</p>}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="add-user-username" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
                {localize("Tên đăng nhập *", "Username *")}
              </label>
              <input
                id="add-user-username"
                type="text"
                autoComplete="username"
                required
                value={formData.username}
                onChange={(e) => {
                  setFormData({ ...formData, username: e.target.value });
                  setFieldErrors((current) => ({ ...current, username: undefined }));
                }}
                aria-invalid={Boolean(fieldErrors.username)}
                aria-describedby={fieldErrors.username ? "add-user-username-error" : undefined}
                className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                placeholder="nguyenvanan"
              />
              {fieldErrors.username && <p id="add-user-username-error" className="mt-1 text-xs text-rose-700">{fieldErrors.username}</p>}
            </div>
            <div>
              <label htmlFor="add-user-password" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
                {localize("Mật khẩu *", "Password *")}
              </label>
              <input
                id="add-user-password"
                type="password"
                autoComplete="new-password"
                required
                value={formData.password}
                onChange={(e) => {
                  setFormData({ ...formData, password: e.target.value });
                  setFieldErrors((current) => ({ ...current, password: undefined }));
                }}
                aria-invalid={Boolean(fieldErrors.password)}
                aria-describedby="add-user-password-help"
                className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                placeholder="••••••••"
              />
              <p id="add-user-password-help" className={`mt-1 text-xs ${fieldErrors.password ? "text-rose-700" : "text-[#66727C]"}`}>{fieldErrors.password || localize("Tối thiểu 8 ký tự.", "At least 8 characters.")}</p>
            </div>
          </div>
          <div>
            <label htmlFor="add-user-email" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
              Email *
            </label>
            <input
              id="add-user-email"
              type="email"
              autoComplete="email"
              required
              value={formData.email}
              onChange={(e) => {
                setFormData({ ...formData, email: e.target.value });
                setFieldErrors((current) => ({ ...current, email: undefined }));
              }}
              aria-invalid={Boolean(fieldErrors.email)}
              aria-describedby={fieldErrors.email ? "add-user-email-error" : undefined}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
              placeholder="name@email.com"
            />
            {fieldErrors.email && <p id="add-user-email-error" className="mt-1 text-xs text-rose-700">{fieldErrors.email}</p>}
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="add-user-phone" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
                {localize("Số điện thoại *", "Phone *")}
              </label>
              <input
                id="add-user-phone"
                type="tel"
                autoComplete="tel"
                required
                value={formData.phone}
                onChange={(e) => {
                  setFormData({ ...formData, phone: e.target.value });
                  setFieldErrors((current) => ({ ...current, phone: undefined }));
                }}
                aria-invalid={Boolean(fieldErrors.phone)}
                aria-describedby={fieldErrors.phone ? "add-user-phone-error" : undefined}
                className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                placeholder="0987654321"
              />
              {fieldErrors.phone && <p id="add-user-phone-error" className="mt-1 text-xs text-rose-700">{fieldErrors.phone}</p>}
            </div>
            <div>
              <label htmlFor="add-user-role" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
                {localize("Vai trò *", "Role *")}
              </label>
              <select
                id="add-user-role"
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
            <label htmlFor="add-user-address" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">
              {localize("Địa chỉ", "Address")}
            </label>
            <input
              id="add-user-address"
              type="text"
              autoComplete="street-address"
              value={formData.address}
              onChange={(e) => setFormData({ ...formData, address: e.target.value })}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm font-medium outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
              placeholder={localize("Địa chỉ liên hệ", "Contact address")}
            />
          </div>
        </div>

        <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
          <Button variant="secondary" disabled={isSubmitting} onClick={onClose}>{localize("Hủy", "Cancel")}</Button>
          <Button type="submit" loading={isSubmitting} loadingLabel={localize("Đang tạo...", "Creating...")}>{localize("Tạo tài khoản", "Create account")}</Button>
        </footer>
      </form>
    </ViewportModal>
  );
}
