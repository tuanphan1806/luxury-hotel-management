"use client";

import React, { useState } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getApiErrorMessage } from "@/lib/api";

interface UserItem {
  id: number;
  fullName: string;
}

interface DeleteGuestModalProps {
  isOpen: boolean;
  onClose: () => void;
  user: UserItem | null;
  onConfirm: () => Promise<void>;
}

export default function DeleteGuestModal({ isOpen, onClose, user, onConfirm }: DeleteGuestModalProps) {
  const { localize } = useLanguage();
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMsg, setErrorMsg] = useState("");

  if (!isOpen || !user) return null;

  const handleConfirm = async () => {
    setIsSubmitting(true);
    setErrorMsg("");
    try {
      await onConfirm();
      onClose();
    } catch (err: unknown) {
      setErrorMsg(getApiErrorMessage(err, "Không thể xóa người dùng."));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget && !isSubmitting) onClose(); }}>
      <div className="bg-white rounded-2xl max-w-sm w-full p-8 space-y-6 shadow-2xl relative text-center border border-gray-150">
        <div className="w-16 h-16 bg-rose-50 text-rose-600 rounded-full flex items-center justify-center mx-auto">
          {/* Warning SVG instead of text emoji */}
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="w-8 h-8"
          >
            <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z" />
            <line x1="12" y1="9" x2="12" y2="13" />
            <line x1="12" y1="17" x2="12.01" y2="17" />
          </svg>
        </div>

        <div className="space-y-2">
          <h3 className="font-serif text-2xl font-bold text-[#0F2A43]">{localize("Xóa người dùng", "Delete user")}</h3>
          <p className="text-xs text-[#66727C] leading-relaxed">
            {localize("Bạn có chắc chắn muốn xóa người dùng", "Are you sure you want to delete user")} <strong>{user.fullName}</strong>? {localize("Hành động này không thể hoàn tác.", "This action cannot be undone.")}
          </p>
        </div>

        {errorMsg && (
          <div className="p-3 bg-rose-50 border border-rose-150 text-rose-600 rounded-lg text-xs font-semibold">
            {errorMsg}
          </div>
        )}

        <div className="flex gap-3 pt-2">
          <button
            type="button"
            disabled={isSubmitting}
            onClick={onClose}
            className="flex-1 py-3 border border-[#0F2A43]/10 hover:bg-[#F1F0EA] text-[#66727C] font-bold text-xs uppercase rounded-xl transition-all"
          >
            {localize("Hủy", "Cancel")}
          </button>
          <button
            type="button"
            disabled={isSubmitting}
            onClick={handleConfirm}
            className="flex-1 py-3 bg-rose-600 hover:bg-rose-700 text-white font-bold text-xs uppercase rounded-xl shadow-md transition-all"
          >
            {isSubmitting ? localize("Đang xóa...", "Deleting...") : localize("Xóa", "Delete")}
          </button>
        </div>
      </div>
    </div>
  );
}
