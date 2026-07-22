"use client";

import React, { useState, useEffect } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import Button from "@/components/UI/Button";
import ViewportModal from "@/components/UI/ViewportModal";

interface MaintenanceFormModalProps {
  isOpen: boolean;
  onClose: () => void;
  roomName: string | undefined;
  roomTypeName: string | undefined;
  onConfirm: (reason: string, expectedDate: string) => void | Promise<void>;
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
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setReason("");
      setFormError("");
      setIsSubmitting(false);
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

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!reason.trim() || !expectedDate.trim()) {
      setFormError(localize("Vui lòng nhập lý do và ngày hoàn thành dự kiến.", "Please enter a reason and expected completion date."));
      return;
    }
    setIsSubmitting(true);
    try {
      await onConfirm(reason.trim(), expectedDate);
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <ViewportModal
      open={isOpen}
      onClose={onClose}
      labelledBy="maintenance-form-title"
      describedBy="maintenance-form-description"
      busy={isSubmitting}
      panelClassName="max-w-md"
      backdropClassName="bg-[#091E30]/62"
    >
      <form
        onSubmit={handleSubmit}
        className="flex min-h-0 flex-col"
      >
        <div className="bg-red-950 p-6 text-white text-center">
          <h3 id="maintenance-form-title" className="text-xl font-bold tracking-wide">{localize("Yêu cầu bảo trì phòng", "Room maintenance request")}</h3>
          <p id="maintenance-form-description" className="text-[10px] font-semibold text-[#C8A35B] uppercase tracking-wider mt-1">
            {localize("Phòng", "Room")} #{roomName} ({roomTypeName || localize("Hạng phòng", "Room type")})
          </p>
        </div>

        <div className="min-h-0 overflow-y-auto p-6 space-y-4 text-xs font-semibold text-text-dark">
          <div>
            <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1.5">
              {localize("Lý do bảo trì", "Maintenance reason")} *
            </label>
            <textarea
              data-modal-autofocus
              value={reason}
              onChange={(e) => {
                setReason(e.target.value);
                if (formError) setFormError("");
              }}
              placeholder={localize("Mô tả sự cố hoặc thiết bị cần sửa chữa...", "Describe the issue or equipment requiring repair...")}
              className="w-full border border-gray-300 px-3.5 py-2.5 rounded-xl text-sm font-medium focus:outline-none h-24 resize-none"
              aria-invalid={Boolean(formError)}
              aria-describedby={formError ? "maintenance-form-error" : undefined}
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
              onChange={(e) => {
                setExpectedDate(e.target.value);
                if (formError) setFormError("");
              }}
              className="w-full border border-gray-300 px-3.5 py-2.5 rounded-xl text-sm font-medium focus:outline-none"
              aria-invalid={Boolean(formError)}
              aria-describedby={formError ? "maintenance-form-error" : undefined}
              required
            />
          </div>
          {formError && <p id="maintenance-form-error" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">{formError}</p>}
        </div>

        <div className="bg-[#F1F0EA] px-6 py-4 flex gap-3 border-t border-gray-200 justify-end">
          <Button variant="secondary" disabled={isSubmitting} onClick={onClose} className="uppercase">
            {localize("Hủy", "Cancel")}
          </Button>
          <Button
            type="submit"
            variant="danger"
            loading={isSubmitting}
            loadingLabel={localize("Đang tạo yêu cầu...", "Creating request...")}
            className="uppercase"
          >
            {localize("Bắt đầu bảo trì", "Start maintenance")}
          </Button>
        </div>
      </form>
    </ViewportModal>
  );
}
