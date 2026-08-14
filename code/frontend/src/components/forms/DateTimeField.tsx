"use client";

import {
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type InputHTMLAttributes,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import { useLanguage } from "@/components/i18n/LanguageProvider";

type DateTimeFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, "type" | "value" | "onChange"> & {
  label: ReactNode;
  value: string;
  onValueChange: (value: string) => void;
  helperText?: ReactNode;
  tone?: "guest" | "operations";
  containerClassName?: string;
};

type Period = "SA" | "CH";

type TimePickerProps = {
  id: string;
  label: string;
  value: string;
  min?: string;
  max?: string;
  step: number;
  disabled: boolean;
  onChange: (value: string) => void;
  className?: string;
};

const padTime = (value: number) => String(value).padStart(2, "0");

const parseTime = (value?: string) => {
  const matched = String(value || "").match(/^(\d{2}):(\d{2})/);
  if (!matched) return null;
  const hour = Number(matched[1]);
  const minute = Number(matched[2]);
  if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;
  return { hour, minute };
};

const toMinutes = (value?: string) => {
  const parsed = parseTime(value);
  return parsed ? parsed.hour * 60 + parsed.minute : null;
};

const toHour24 = (hour12: number, period: Period) => {
  if (period === "SA") return hour12 === 12 ? 0 : hour12;
  return hour12 === 12 ? 12 : hour12 + 12;
};

const toHour12 = (hour24: number) => hour24 % 12 || 12;

const displayTime = (value: string, morningLabel: string, afternoonLabel: string) => {
  const parsed = parseTime(value);
  if (!parsed) return "--:--";
  const period: Period = parsed.hour < 12 ? "SA" : "CH";
  return `${padTime(toHour12(parsed.hour))}:${padTime(parsed.minute)} ${period === "SA" ? morningLabel : afternoonLabel}`;
};

function NativeStyleTimePicker({ id, label, value, min, max, step, disabled, onChange, className = "" }: TimePickerProps) {
  const { localize } = useLanguage();
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [position, setPosition] = useState({ left: 12, top: 12, width: 252 });
  const parsed = parseTime(value) ?? { hour: 14, minute: 0 };
  const selectedHour12 = toHour12(parsed.hour);
  const selectedPeriod: Period = parsed.hour < 12 ? "SA" : "CH";
  const minimum = toMinutes(min);
  const maximum = toMinutes(max);
  const minuteStep = Math.max(1, Math.floor(Number(step || 60) / 60));

  const minuteOptions = useMemo(() => {
    const options = Array.from({ length: Math.ceil(60 / minuteStep) }, (_, index) => index * minuteStep)
      .filter((minute) => minute < 60);
    if (!options.includes(parsed.minute)) options.push(parsed.minute);
    return options.sort((left, right) => left - right);
  }, [minuteStep, parsed.minute]);

  const isAllowed = (hour24: number, minute: number) => {
    const candidate = hour24 * 60 + minute;
    return (minimum == null || candidate >= minimum) && (maximum == null || candidate <= maximum);
  };

  const allowedMinutes = (hour12: number, period: Period) => {
    const hour24 = toHour24(hour12, period);
    return minuteOptions.filter((minute) => isAllowed(hour24, minute));
  };

  const commit = (hour12: number, minute: number, period: Period) => {
    const hour24 = toHour24(hour12, period);
    if (!isAllowed(hour24, minute)) return;
    onChange(`${padTime(hour24)}:${padTime(minute)}`);
  };

  const chooseHour = (hour12: number) => {
    const validMinutes = allowedMinutes(hour12, selectedPeriod);
    if (validMinutes.length === 0) return;
    commit(hour12, validMinutes.includes(parsed.minute) ? parsed.minute : validMinutes[0], selectedPeriod);
  };

  const chooseMinute = (minute: number) => commit(selectedHour12, minute, selectedPeriod);

  const choosePeriod = (period: Period) => {
    const currentMinutes = allowedMinutes(selectedHour12, period);
    if (currentMinutes.length > 0) {
      commit(selectedHour12, currentMinutes.includes(parsed.minute) ? parsed.minute : currentMinutes[0], period);
      return;
    }

    for (let hour = 1; hour <= 12; hour += 1) {
      const validMinutes = allowedMinutes(hour, period);
      if (validMinutes.length > 0) {
        commit(hour, validMinutes[0], period);
        return;
      }
    }
  };

  const periodAvailable = (period: Period) => Array.from({ length: 12 }, (_, index) => index + 1)
    .some((hour) => allowedMinutes(hour, period).length > 0);

  useLayoutEffect(() => {
    if (!open) return;

    const updatePosition = () => {
      const trigger = triggerRef.current;
      if (!trigger) return;
      const triggerRect = trigger.getBoundingClientRect();
      const menuWidth = Math.min(264, Math.max(232, window.innerWidth - 24));
      const menuHeight = menuRef.current?.getBoundingClientRect().height || 308;
      const belowTop = triggerRect.bottom + 8;
      const top = belowTop + menuHeight <= window.innerHeight - 12
        ? belowTop
        : Math.max(12, triggerRect.top - menuHeight - 8);
      const left = Math.min(
        Math.max(12, triggerRect.right - menuWidth),
        Math.max(12, window.innerWidth - menuWidth - 12),
      );
      setPosition({ left, top, width: menuWidth });
    };

    updatePosition();
    window.addEventListener("resize", updatePosition);
    window.addEventListener("scroll", updatePosition, true);
    return () => {
      window.removeEventListener("resize", updatePosition);
      window.removeEventListener("scroll", updatePosition, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!triggerRef.current?.contains(target) && !menuRef.current?.contains(target)) setOpen(false);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };

    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    const frame = window.requestAnimationFrame(() => {
      menuRef.current?.querySelectorAll<HTMLElement>("[aria-selected='true']")
        .forEach((option) => option.scrollIntoView({ block: "center" }));
    });
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
      window.cancelAnimationFrame(frame);
    };
  }, [open]);

  const dropdown = open ? (
    <div
      ref={menuRef}
      role="dialog"
      aria-label={label}
      style={{ left: position.left, top: position.top, width: position.width }}
      className="hotel-native-time-menu fixed z-[1000] grid grid-cols-[1fr_1fr_0.86fr] gap-1 rounded-xl border border-[#0F2A43]/15 bg-[#FBFAF6] p-2 shadow-[0_18px_45px_rgba(15,42,67,0.22)]"
    >
      <div role="listbox" aria-label={localize("Giờ", "Hour")} className="lux-scrollbar hotel-native-time-list max-h-56 space-y-1 overflow-y-auto pr-1">
        {Array.from({ length: 12 }, (_, index) => index + 1).map((hour) => {
          const selected = hour === selectedHour12;
          const unavailable = allowedMinutes(hour, selectedPeriod).length === 0;
          return (
            <button
              key={hour}
              type="button"
              role="option"
              aria-selected={selected}
              disabled={unavailable}
              onClick={() => chooseHour(hour)}
              className={`hotel-native-time-option min-h-11 w-full rounded-lg text-sm font-bold tabular-nums transition duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-30 ${selected ? "bg-[#0F2A43] text-white" : "text-[#0F2A43] hover:bg-[#EAE2D2]"}`}
            >
              {padTime(hour)}
            </button>
          );
        })}
      </div>

      <div role="listbox" aria-label={localize("Phút", "Minute")} className="lux-scrollbar hotel-native-time-list max-h-56 space-y-1 overflow-y-auto border-x border-[#0F2A43]/10 px-1">
        {minuteOptions.map((minute) => {
          const selected = minute === parsed.minute;
          const unavailable = !isAllowed(parsed.hour, minute);
          return (
            <button
              key={minute}
              type="button"
              role="option"
              aria-selected={selected}
              disabled={unavailable}
              onClick={() => chooseMinute(minute)}
              className={`hotel-native-time-option min-h-11 w-full rounded-lg text-sm font-bold tabular-nums transition duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-30 ${selected ? "bg-[#B8944F] text-[#0F2A43]" : "text-[#0F2A43] hover:bg-[#EAE2D2]"}`}
            >
              {padTime(minute)}
            </button>
          );
        })}
      </div>

      <div role="listbox" aria-label={localize("Buổi", "Period")} className="space-y-1">
        {(["SA", "CH"] as Period[]).map((period) => {
          const selected = period === selectedPeriod;
          return (
            <button
              key={period}
              type="button"
              role="option"
              aria-selected={selected}
              disabled={!periodAvailable(period)}
              onClick={() => choosePeriod(period)}
              className={`hotel-native-time-option min-h-11 w-full rounded-lg text-sm font-bold transition duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-30 ${selected ? "bg-[#0F2A43] text-white" : "text-[#0F2A43] hover:bg-[#EAE2D2]"}`}
            >
              {period === "SA" ? localize("SA", "AM") : localize("CH", "PM")}
            </button>
          );
        })}
      </div>
    </div>
  ) : null;

  return (
    <>
      <button
        ref={triggerRef}
        id={id}
        type="button"
        aria-label={label}
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((current) => !current)}
        className={`hotel-time-input flex min-h-7 min-w-0 items-center justify-between gap-2 appearance-none text-left text-sm font-bold tabular-nums text-[#0F2A43] outline-none transition focus-visible:ring-2 focus-visible:ring-[#B8944F]/50 disabled:cursor-not-allowed disabled:text-[#66727C] ${className || "bg-transparent"}`}
      >
        <span>{displayTime(value, localize("SA", "AM"), localize("CH", "PM"))}</span>
        <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4 shrink-0 text-[#66727C]" fill="none" stroke="currentColor" strokeWidth="1.8">
          <circle cx="12" cy="12" r="8.5" />
          <path d="M12 7.5v5l3.25 1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      {dropdown && createPortal(dropdown, document.body)}
    </>
  );
}

export type TimePickerInputProps = {
  id?: string;
  label: string;
  value: string;
  min?: string;
  max?: string;
  step?: number;
  disabled?: boolean;
  onValueChange: (value: string) => void;
  className?: string;
};

/**
 * Reuses the same styled time menu outside DateTimeField without changing the
 * stored HH:mm value or the surrounding form layout.
 */
export function TimePickerInput({
  id,
  label,
  value,
  min,
  max,
  step = 60,
  disabled = false,
  onValueChange,
  className = "",
}: TimePickerInputProps) {
  const generatedId = useId();
  return (
    <NativeStyleTimePicker
      id={id || generatedId}
      label={label}
      value={value}
      min={min}
      max={max}
      step={step}
      disabled={disabled}
      onChange={onValueChange}
      className={className}
    />
  );
}

/**
 * Separate date and time controls remain easy to scan. The custom time popup
 * keeps the native three-column content while allowing consistent hotel styling.
 * The public value remains yyyy-MM-ddTHH:mm.
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
  step = 60,
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
        <div className="grid min-w-0 gap-1 px-3 py-2.5">
          <span className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#80632F]">{localize("Giờ", "Time")}</span>
          <NativeStyleTimePicker
            id={`${fieldId}-time`}
            label={`${labelText} — ${localize("giờ", "time")}`}
            value={timeValue.slice(0, 5)}
            min={dateValue && dateValue === minDate ? minTime.slice(0, 5) || undefined : undefined}
            max={dateValue && dateValue === maxDate ? maxTime.slice(0, 5) || undefined : undefined}
            step={Number(step) || 60}
            disabled={Boolean(disabled || !dateValue)}
            onChange={updateTime}
          />
        </div>
      </div>
      {helperText && <span className="text-xs font-medium leading-5 text-[#66727C]">{helperText}</span>}
    </div>
  );
}
