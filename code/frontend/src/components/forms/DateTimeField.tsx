"use client";

import { useId, type InputHTMLAttributes, type ReactNode } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";

type DateTimeFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "value" | "onChange"> & {
  label: ReactNode;
  value: string;
  onValueChange: (value: string) => void;
  helperText?: ReactNode;
  tone?: "guest" | "operations";
  containerClassName?: string;
};

/**
 * Separate native date and time controls are easier to scan and preserve the
 * platform picker on mobile. The public value remains yyyy-MM-ddTHH:mm.
 */
export default function DateTimeField({
  label,
  value,
  onValueChange,
  helperText,
  tone = "guest",
  containerClassName = "",
  className = "",
  id,
  min,
  max,
  disabled,
  required,
  ...inputProps
}: DateTimeFieldProps) {
  const { localize } = useLanguage();
  const generatedId = useId();
  const operational = tone === "operations";
  const fieldId = id || generatedId;
  const [dateValue = "", timeValue = ""] = String(value || "").split("T");
  const [minDate = "", minTime = ""] = String(min || "").split("T");
  const [maxDate = "", maxTime = ""] = String(max || "").split("T");
  const labelText = typeof label === "string" ? label : localize("Thời gian lưu trú", "Stay time");

  const updateDate = (nextDate: string) => {
    if (!nextDate) {
      onValueChange("");
      return;
    }
    onValueChange(`${nextDate}T${timeValue || "14:00"}`);
  };

  const updateTime = (nextTime: string) => {
    if (!dateValue || !nextTime) return;
    onValueChange(`${dateValue}T${nextTime}`);
  };

  const surfaceClass = operational
    ? "rounded-lg border-[#0F2A43]/14 bg-white"
    : "rounded-xl border-[#0F2A43]/14 bg-[#FBFAF6] shadow-[0_10px_28px_rgba(15,42,67,0.07)]";

  return (
    <div className={`grid min-w-0 gap-2 ${containerClassName}`}>
      <div className="flex items-center justify-between gap-3">
        <span className={`font-semibold text-[#66727C] ${operational ? "text-xs" : "text-sm"}`}>{label}</span>
        <span className="text-[10px] font-semibold text-[#66727C]">{localize("Giờ địa phương", "Local time")}</span>
      </div>
      <div className={`grid grid-cols-[minmax(0,1.2fr)_minmax(6.5rem,0.8fr)] overflow-hidden border transition duration-200 focus-within:border-[#B8944F] focus-within:ring-4 focus-within:ring-[#B8944F]/15 ${surfaceClass} ${className}`}>
        <label className="grid min-w-0 gap-1 border-r border-[#0F2A43]/10 px-3 py-2.5">
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#80632F]">{localize("Ngày", "Date")}</span>
          <input
            {...inputProps}
            id={`${fieldId}-date`}
            type="date"
            aria-label={`${labelText} — ${localize("ngày", "date")}`}
            value={dateValue}
            min={minDate || undefined}
            max={maxDate || undefined}
            disabled={disabled}
            required={required}
            onChange={(event) => updateDate(event.target.value)}
            className="hotel-date-input min-h-7 min-w-0 appearance-none bg-transparent text-sm font-bold tabular-nums text-[#0F2A43] outline-none disabled:cursor-not-allowed disabled:text-[#66727C]"
          />
        </label>
        <label className="grid min-w-0 gap-1 px-3 py-2.5">
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#80632F]">{localize("Giờ", "Time")}</span>
          <input
            id={`${fieldId}-time`}
            type="time"
            aria-label={`${labelText} — ${localize("giờ", "time")}`}
            value={timeValue}
            min={dateValue && dateValue === minDate ? minTime || undefined : undefined}
            max={dateValue && dateValue === maxDate ? maxTime || undefined : undefined}
            disabled={disabled || !dateValue}
            required={required}
            step={900}
            onChange={(event) => updateTime(event.target.value)}
            className="hotel-time-input min-h-7 min-w-0 appearance-none bg-transparent text-sm font-bold tabular-nums text-[#0F2A43] outline-none disabled:cursor-not-allowed disabled:text-[#66727C]"
          />
        </label>
      </div>
      {helperText && <span className="text-xs font-medium leading-5 text-[#66727C]">{helperText}</span>}
    </div>
  );
}
