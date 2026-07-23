"use client";

import React, { useState } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import Button from "@/components/UI/Button";
import ViewportModal from "@/components/UI/ViewportModal";

interface CleanConfirmModalProps {
  isOpen: boolean;
  onClose: () => void;
  roomName: string | undefined;
  targetStatus: string;
  onConfirm: () => void | Promise<void>;
}

export default function CleanConfirmModal({
  isOpen,
  onClose,
  roomName,
  targetStatus,
  onConfirm,
}: CleanConfirmModalProps) {
  const { localize } = useLanguage();
  const [isSubmitting, setIsSubmitting] = useState(false);
  if (!isOpen || !roomName) return null;

  const handleConfirm = async () => {
    setIsSubmitting(true);
    try {
      await onConfirm();
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <ViewportModal
      open={isOpen}
      onClose={onClose}
      labelledBy="clean-confirm-title"
      busy={isSubmitting}
      panelClassName="max-w-sm"
      backdropClassName="bg-[#091E30]/62"
    >
      <div className="min-h-0 overflow-y-auto p-6 space-y-4 text-xs font-semibold text-text-dark">
        <div>
          <h3 id="clean-confirm-title" className="text-lg font-bold text-[#0F2A43]">{localize("Xác nhận cập nhật vệ sinh", "Confirm housekeeping update")}</h3>
          <p className="text-xs text-[#66727C] mt-1">
            {localize("Chuyển trạng thái vệ sinh của phòng", "Change the housekeeping status for room")}{" "}
            <span className="font-bold text-[#0F2A43]">#{roomName}</span> {localize("thành", "to")}{" "}
            <span className="font-bold text-[#0F2A43]">{{ CLEAN: localize("Sạch", "Clean"), DIRTY: localize("Bẩn", "Dirty"), IN_PROGRESS: localize("Đang dọn", "Cleaning") }[targetStatus] || targetStatus}</span>?
          </p>
        </div>
        <div className="flex gap-2 justify-end pt-2">
          <Button variant="secondary" disabled={isSubmitting} onClick={onClose} className="uppercase">
            {localize("Hủy", "Cancel")}
          </Button>
          <Button
            loading={isSubmitting}
            loadingLabel={localize("Đang cập nhật...", "Updating...")}
            onClick={() => void handleConfirm()}
            className="uppercase"
          >
            {localize("Xác nhận", "Confirm")}
          </Button>
        </div>
      </div>
    </ViewportModal>
  );
}
