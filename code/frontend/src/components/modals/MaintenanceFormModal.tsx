"use client";

import React, { useState, useEffect } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";

interface MaintenanceFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  roomName: string | undefined;
  roomTypeName: string | undefined;
  onConfirm: (reason: string, expectedDate: string) => void;
}

export default function MaintenanceFormModal({
  isOpen,
  onClose,
  roomName,
  roomTypeName,
  onConfirm,
}: MaintenanceFormModalProps) {
  const { localize } = useLanguage();
  const [reason, setReason] = useState("");
  const [expectedDate, setExpectedDate] = useState("");
  const [formError, setFormError] = useState("");
  const [minDate, setMinDate] = useState("");

  useEffect(() => {
    if (isOpen) {
      setReason("");
      setFormError("");
      const tomorrow = new Date();
      const today = new Date();
      const todayOffset = today.getTimezoneOffset() * 60_000;
      setMinDate(new Date(today.getTime() - todayOffset).toISOString().slice(0, 10));
      tomorrow.setDate(tomorrow.getDate() + 1);
      const offset = tomorrow.getTimezoneOffset() * 60_000;
      setExpectedDate(new Date(tomorrow.getTime() - offset).toISOString().slice(0, 10));
    }
  }, [isOpen]);

  if (!isOpen || !roomName) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!reason.trim() || !expectedDate.trim()) {
      setFormError(localize("Vui lòng nhập lý do và ngày hoàn thành dự kiến.", "Please enter a reason and expected completion date."));
      return;
    }
    onConfirm(reason, expectedDate);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <form
        onSubmit={handleSubmit}
        className="bg-white border border-gray-200 rounded-xl shadow-2xl max-w-md w-full overflow-hidden"
      >
        <div className="bg-red-950 p-6 text-white text-center">
          <h3 className="font-serif text-xl font-bold tracking-wide">{localize("Yêu cầu bảo trì phòng", "Room maintenance request")}</h3>
          <p className="text-[10px] font-semibold text-[#C8A35B] uppercase tracking-wider mt-1">
            {localize("Phòng", "Room")} #{roomName} ({roomTypeName || localize("Hạng phòng", "Room type")})
          </p>
        </div>

        <div className="p-6 space-y-4 text-xs font-semibold text-text-dark">
          <div>
            <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1.5">
              {localize("Lý do bảo trì", "Maintenance reason")} *
            </label>
            <textarea
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder={localize("Mô tả sự cố hoặc thiết bị cần sửa chữa...", "Describe the issue or equipment requiring repair...")}
              className="w-full border border-gray-300 px-3.5 py-2.5 rounded-xl text-sm font-medium focus:outline-none h-24 resize-none"
              required
            />
          </div>

          <div>
            <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1.5">
              {localize("Ngày hoàn thành dự kiến", "Expected completion date")} *
            </label>
            <input
              type="date"
              min={minDate}
              value={expectedDate}
              onChange={(e) => setExpectedDate(e.target.value)}
              className="w-full border border-gray-300 px-3.5 py-2.5 rounded-xl text-sm font-medium focus:outline-none"
              required
            />
          </div>
          {formError && <p className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{formError}</p>}
        </div>

        <div className="bg-[#F1F0EA] px-6 py-4 flex gap-3 border-t border-gray-200 justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4.5 py-2.5 border border-gray-350 hover:bg-gray-100 rounded-xl uppercase transition-colors"
          >
            {localize("Hủy", "Cancel")}
          </button>
          <button
            type="submit"
            className="px-5 py-2.5 bg-red-800 hover:bg-red-900 text-white font-bold tracking-wider uppercase transition-colors rounded-xl shadow-sm"
          >
            {localize("Bắt đầu bảo trì", "Start maintenance")}
          </button>
        </div>
      </form>
    </div>
  );
}
