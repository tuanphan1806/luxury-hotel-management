"use client";

import React, { useState, useEffect } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getApiErrorMessage } from "@/lib/api";

type UserRole = "CUSTOMER" | "STAFF" | "ADMIN";

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
    }
  }, [user, isOpen]);

  if (!isOpen || !user) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (
      !formData.fullName.trim() ||
      !formData.username.trim() ||
      !formData.email.trim() ||
      !formData.phone.trim()
    ) {
      setErrorMsg("Vui lòng điền đầy đủ các trường bắt buộc (*)");
      return;
    }
    if (formData.password && formData.password.length < 8) {
      setErrorMsg("Mật khẩu mới phải có ít nhất 8 ký tự");
      return;
    }
    if (formData.password !== formData.confirmPassword) {
      setErrorMsg("Mật khẩu mới và xác nhận mật khẩu không khớp");
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
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget && !isSubmitting) onClose(); }}>
      <div className="bg-white rounded-2xl max-w-md w-full p-8 space-y-6 shadow-2xl relative border border-gray-150">
        <div>
          <h3 className="font-serif text-2xl font-bold text-[#0F2A43]">{localize("Chỉnh sửa người dùng", "Edit user details")}</h3>
          <p className="text-xs text-[#66727C] mt-1">{localize("Cập nhật thông tin chi tiết của người dùng hệ thống.", "Update the selected user's account details.")}</p>
        </div>

        {errorMsg && (
          <div className="p-3 bg-rose-50 border border-rose-150 text-rose-600 rounded-lg text-xs font-semibold">
            {errorMsg}
          </div>
        )}

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C] mb-1.5">
              {localize("Họ và tên *", "Full name *")}
            </label>
            <input
              type="text"
              required
              value={formData.fullName}
              onChange={(e) => setFormData({ ...formData, fullName: e.target.value })}
              className="w-full px-4 py-2.5 rounded-xl border border-[#0F2A43]/10 focus:border-[#B8944F] focus:outline-none text-xs font-semibold"
            />
          </div>

          <div className="rounded-xl border border-amber-200 bg-amber-50/60 p-4">
            <p className="mb-3 text-[10px] font-bold uppercase tracking-wider text-amber-800">{localize("Quản trị viên đặt lại mật khẩu", "Administrator password reset")}</p>
            <div className="grid grid-cols-2 gap-3">
              <input type="password" value={formData.password} onChange={(e) => setFormData({ ...formData, password: e.target.value })} placeholder={localize("Mật khẩu mới", "New password")} className="w-full rounded-xl border border-amber-200 bg-white px-3 py-2.5 text-xs font-semibold outline-none focus:border-amber-500" />
              <input type="password" value={formData.confirmPassword} onChange={(e) => setFormData({ ...formData, confirmPassword: e.target.value })} placeholder={localize("Xác nhận mật khẩu", "Confirm password")} className="w-full rounded-xl border border-amber-200 bg-white px-3 py-2.5 text-xs font-semibold outline-none focus:border-amber-500" />
            </div>
            <p className="mt-2 text-[10px] font-medium text-amber-800">Để trống nếu chỉ cập nhật thông tin người dùng.</p>
          </div>
          <div>
            <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C] mb-1.5">
              {localize("Tên đăng nhập *", "Username *")}
            </label>
            <input
              type="text"
              required
              value={formData.username}
              onChange={(e) => setFormData({ ...formData, username: e.target.value })}
              className="w-full px-4 py-2.5 rounded-xl border border-[#0F2A43]/10 focus:border-[#B8944F] focus:outline-none text-xs font-semibold"
            />
          </div>
          <div>
            <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C] mb-1.5">
              Email *
            </label>
            <input
              type="email"
              required
              value={formData.email}
              onChange={(e) => setFormData({ ...formData, email: e.target.value })}
              className="w-full px-4 py-2.5 rounded-xl border border-[#0F2A43]/10 focus:border-[#B8944F] focus:outline-none text-xs font-semibold"
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C] mb-1.5">
                {localize("Số điện thoại *", "Phone *")}
              </label>
              <input
                type="text"
                required
                value={formData.phone}
                onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
                className="w-full px-4 py-2.5 rounded-xl border border-[#0F2A43]/10 focus:border-[#B8944F] focus:outline-none text-xs font-semibold"
              />
            </div>
            <div>
              <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C] mb-1.5">
                {localize("Vai trò *", "Role *")}
              </label>
              <select
                value={formData.type}
                onChange={(e) => setFormData({ ...formData, type: e.target.value as UserRole })}
                className="w-full px-4 py-2.5 rounded-xl border border-[#0F2A43]/10 focus:border-[#B8944F] focus:outline-none text-xs font-bold bg-white"
              >
                <option value="CUSTOMER">CUSTOMER</option>
                <option value="STAFF">STAFF</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </div>
          </div>
          <div>
            <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C] mb-1.5">
              {localize("Địa chỉ", "Address")}
            </label>
            <input
              type="text"
              value={formData.address}
              onChange={(e) => setFormData({ ...formData, address: e.target.value })}
              className="w-full px-4 py-2.5 rounded-xl border border-[#0F2A43]/10 focus:border-[#B8944F] focus:outline-none text-xs font-semibold"
            />
          </div>

          <div className="flex justify-end gap-3 pt-4">
            <button
              type="button"
              disabled={isSubmitting}
              onClick={onClose}
              className="px-5 py-2.5 border border-[#0F2A43]/10 hover:bg-[#F1F0EA] text-[#66727C] font-bold text-xs uppercase rounded-xl transition-all"
            >
              {localize("Hủy", "Cancel")}
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="px-5 py-2.5 bg-[#B8944F] hover:bg-[#967538] text-[#0F2A43] font-bold text-xs uppercase rounded-xl shadow-sm transition-all flex items-center gap-2"
            >
              {isSubmitting ? localize("Đang lưu...", "Saving...") : localize("Lưu thay đổi", "Save changes")}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
