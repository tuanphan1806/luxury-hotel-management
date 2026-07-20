"use client";

import React from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";

interface CleanConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  roomName: string | undefined;
  targetStatus: string;
  onConfirm: () => void;
}

export default function CleanConfirmModal({
  isOpen,
  onClose,
  roomName,
  targetStatus,
  onConfirm,
}: CleanConfirmModalProps) {
  const { localize } = useLanguage();
  if (!isOpen || !roomName) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="bg-white border border-gray-200 rounded-xl shadow-2xl max-w-sm w-full p-6 space-y-4 text-xs font-semibold text-text-dark">
        <div>
          <h3 className="font-serif text-lg font-bold text-[#0F2A43]">{localize("Xác nhận cập nhật vệ sinh", "Confirm housekeeping update")}</h3>
          <p className="text-xs text-[#66727C] mt-1">
            {localize("Chuyển trạng thái vệ sinh của phòng", "Change the housekeeping status for room")}{" "}
            <span className="font-bold text-[#0F2A43]">#{roomName}</span> {localize("thành", "to")}{" "}
            <span className="font-bold text-[#0F2A43]">{{ CLEAN: localize("Sạch", "Clean"), DIRTY: localize("Bẩn", "Dirty"), IN_PROGRESS: localize("Đang dọn", "Cleaning") }[targetStatus] || targetStatus}</span>?
          </p>
        </div>
        <div className="flex gap-2 justify-end pt-2">
          <button
            type="button"
            onClick={onClose}
            className="px-4.5 py-2 border border-gray-350 hover:bg-gray-100 rounded-xl uppercase transition-colors"
          >
            {localize("Hủy", "Cancel")}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            className="px-4.5 py-2 bg-[#B8944F] hover:bg-[#967538] text-white font-bold uppercase transition-colors rounded-xl shadow-sm"
          >
            {localize("Xác nhận", "Confirm")}
          </button>
        </div>
      </div>
    </div>
  );
}
