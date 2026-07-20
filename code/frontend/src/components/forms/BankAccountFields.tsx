"use client";

import { useId } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";

export const VIETNAMESE_BANKS = [
  { code: "VCB", name: "Vietcombank" },
  { code: "BIDV", name: "BIDV" },
  { code: "ICB", name: "VietinBank" },
  { code: "VBA", name: "Agribank" },
  { code: "TCB", name: "Techcombank" },
  { code: "MB", name: "MBBank" },
  { code: "ACB", name: "ACB" },
  { code: "VPB", name: "VPBank" },
  { code: "TPB", name: "TPBank" },
  { code: "STB", name: "Sacombank" },
  { code: "VIB", name: "VIB" },
  { code: "HDB", name: "HDBank" },
  { code: "SHB", name: "SHB" },
  { code: "MSB", name: "MSB" },
  { code: "OCB", name: "OCB" },
  { code: "LPB", name: "LPBank" },
  { code: "EIB", name: "Eximbank" },
  { code: "SCB", name: "SCB" },
  { code: "SEAB", name: "SeABank" },
  { code: "NAB", name: "NamABank" },
] as const;

interface BankAccountFieldsProps {
  bankCode: string;
  bankName: string;
  accountNumber: string;
  accountHolderName: string;
  onBankChange: (bankCode: string, bankName: string) => void;
  onAccountNumberChange: (value: string) => void;
  onAccountHolderNameChange: (value: string) => void;
  disabled?: boolean;
  className?: string;
  error?: boolean;
}

export default function BankAccountFields({
  bankCode,
  bankName,
  accountNumber,
  accountHolderName,
  onBankChange,
  onAccountNumberChange,
  onAccountHolderNameChange,
  disabled = false,
  className = "",
  error = false,
}: BankAccountFieldsProps) {
  const { localize } = useLanguage();
  const id = useId();
  const selectedBankValue = VIETNAMESE_BANKS.some((bank) => bank.code === bankCode)
    ? bankCode
    : "";

  return (
    <fieldset disabled={disabled} className={`grid gap-4 sm:grid-cols-2 ${className}`}>
      <legend className="sr-only">{localize("Chi tiết tài khoản ngân hàng", "Bank account details")}</legend>

      <label htmlFor={`${id}-bank`} className="block text-sm font-semibold text-[#0F2A43] sm:col-span-2">
        {localize("Ngân hàng", "Bank")} *
        <select
          id={`${id}-bank`}
          value={selectedBankValue}
          onChange={(event) => {
            const selected = VIETNAMESE_BANKS.find((bank) => bank.code === event.target.value);
            onBankChange(selected?.code || "", selected?.name || "");
          }}
          required
          aria-invalid={error && !bankCode}
          className="mt-2 min-h-12 w-full rounded-xl border border-[#0F2A43]/15 bg-white px-3 text-sm font-semibold outline-none transition focus:border-[#B8944F] focus:ring-4 focus:ring-[#B8944F]/15 disabled:cursor-not-allowed disabled:bg-[#F1F0EA]"
        >
          <option value="">{localize("Chọn ngân hàng", "Select a bank")}</option>
          {VIETNAMESE_BANKS.map((bank) => (
            <option key={bank.code} value={bank.code}>{bank.name} ({bank.code})</option>
          ))}
        </select>
        {bankCode && !selectedBankValue && (
          <span className="mt-1 block text-xs font-normal text-amber-800">
            {localize(`Ngân hàng hiện tại: ${bankName || bankCode}. Hãy chọn lại trong danh sách nếu muốn cập nhật.`, `Current bank: ${bankName || bankCode}. Select it again from the list to update.`)}
          </span>
        )}
      </label>

      <label htmlFor={`${id}-account`} className="block text-sm font-semibold text-[#0F2A43]">
        {localize("Số tài khoản", "Account number")} *
        <input
          id={`${id}-account`}
          value={accountNumber}
          onChange={(event) => onAccountNumberChange(event.target.value.replace(/\D/g, "").slice(0, 24))}
          minLength={6}
          maxLength={24}
          pattern="[0-9]{6,24}"
          inputMode="numeric"
          autoComplete="off"
          required
          aria-invalid={error && !/^\d{6,24}$/.test(accountNumber)}
          placeholder={localize("6–24 chữ số", "6–24 digits")}
          className="mt-2 min-h-12 w-full rounded-xl border border-[#0F2A43]/15 bg-white px-3 font-mono text-sm tracking-wide outline-none transition focus:border-[#B8944F] focus:ring-4 focus:ring-[#B8944F]/15 disabled:cursor-not-allowed disabled:bg-[#F1F0EA]"
        />
      </label>

      <label htmlFor={`${id}-holder`} className="block text-sm font-semibold text-[#0F2A43]">
        {localize("Họ tên chủ tài khoản", "Account holder name")} *
        <input
          id={`${id}-holder`}
          value={accountHolderName}
          onChange={(event) => onAccountHolderNameChange(event.target.value.toLocaleUpperCase("vi-VN").slice(0, 100))}
          minLength={2}
          maxLength={100}
          autoComplete="name"
          required
          aria-invalid={error && accountHolderName.trim().length < 2}
          placeholder="NGUYEN VAN A"
          className="mt-2 min-h-12 w-full rounded-xl border border-[#0F2A43]/15 bg-white px-3 text-sm font-semibold uppercase outline-none transition focus:border-[#B8944F] focus:ring-4 focus:ring-[#B8944F]/15 disabled:cursor-not-allowed disabled:bg-[#F1F0EA]"
        />
      </label>
    </fieldset>
  );
}
