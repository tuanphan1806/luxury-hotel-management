"use client";

import React, { useState } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getApiErrorMessage } from "@/lib/api";

type UserRole = "CUSTOMER" | "STAFF" | "ADMIN";

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

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (
      !formData.fullName.trim() ||
      !formData.username.trim() ||
      !formData.email.trim() ||
      !formData.phone.trim() ||
      !formData.password.trim()
    ) {
      setErrorMsg("Vui lòng điền đầy đủ các trường bắt buộc (*)");
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
      onClose();
    } catch (err: unknown) {
      setErrorMsg(getApiErrorMessage(err, "Không thể tạo người dùng. Vui lòng kiểm tra lại."));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget && !isSubmitting) onClose(); }}>
      <div className="bg-white rounded-2xl max-w-md w-full p-8 space-y-6 shadow-2xl relative border border-gray-150">
        <div>
          <h3 className="font-serif text-2xl font-bold text-[#0F2A43]">{localize("Thêm người dùng", "Add new user")}</h3>
          <p className="text-xs text-[#66727C] mt-1">{localize("Tạo mới một tài khoản khách hàng, nhân viên hoặc quản trị viên.", "Create a customer, staff or administrator account.")}</p>
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
              placeholder="e.g. Quách Đức Huy"
            />
          </div>
          <div className="grid grid-cols-2 gap-4">
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
                placeholder="e.g. huyquach"
              />
            </div>
            <div>
              <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C] mb-1.5">
                {localize("Mật khẩu *", "Password *")}
              </label>
              <input
                type="password"
                required
                value={formData.password}
                onChange={(e) => setFormData({ ...formData, password: e.target.value })}
                className="w-full px-4 py-2.5 rounded-xl border border-[#0F2A43]/10 focus:border-[#B8944F] focus:outline-none text-xs font-semibold"
                placeholder="••••••••"
              />
            </div>
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
              placeholder="name@email.com"
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
                placeholder="0987654321"
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
              placeholder="Paris, France"
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
              {isSubmitting ? localize("Đang tạo...", "Creating...") : localize("Tạo", "Create")}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
