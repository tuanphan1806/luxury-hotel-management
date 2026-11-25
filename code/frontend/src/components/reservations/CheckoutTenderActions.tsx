"use client";

import { useLanguage } from "@/components/i18n/LanguageProvider";

export default function CheckoutTenderActions({ disabled, onCash, onQr }: {
  disabled: boolean; onCash: () => void; onQr: () => void;
}) {
  const { localize } = useLanguage();
  return <div className="mt-3 flex flex-wrap gap-2">
    <button type="button" disabled={disabled} onClick={onCash} className="min-h-10 rounded-lg bg-[#B8944F] px-4 text-xs font-bold text-[#0F2A43] disabled:opacity-60">{localize("Thu tiền mặt", "Collect cash")}</button>
    <button type="button" disabled={disabled} onClick={onQr} className="min-h-10 rounded-lg border border-[#0F2A43] bg-white px-4 text-xs font-bold text-[#0F2A43] hover:bg-[#0F2A43] hover:text-white disabled:opacity-60">{localize("Tạo QR", "Create QR")}</button>
  </div>;
}
