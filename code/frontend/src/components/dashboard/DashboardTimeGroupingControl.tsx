"use client";

import type { DashboardTimeGrouping } from "@/lib/dashboard-time";

interface DashboardTimeGroupingControlProps {
  value: DashboardTimeGrouping;
  onChange: (value: DashboardTimeGrouping) => void;
  title: string;
  ariaLabel: string;
  labels: {
    day: string;
    week: string;
    month: string;
  };
}

const OPTIONS: Array<{ value: DashboardTimeGrouping; labelKey: keyof DashboardTimeGroupingControlProps["labels"] }> = [
  { value: "DAY", labelKey: "day" },
  { value: "WEEK", labelKey: "week" },
  { value: "MONTH", labelKey: "month" },
];

export default function DashboardTimeGroupingControl({
  value,
  onChange,
  title,
  ariaLabel,
  labels,
}: DashboardTimeGroupingControlProps) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-[11px] font-bold uppercase tracking-[0.1em] text-[#66727C]">{title}</span>
      <div
        role="group"
        aria-label={ariaLabel}
        className="inline-flex rounded-lg border border-[#0F2A43]/15 bg-[#F1F0EA] p-1"
      >
        {OPTIONS.map((option) => {
          const selected = value === option.value;
          return (
            <button
              key={option.value}
              type="button"
              aria-pressed={selected}
              onClick={() => onChange(option.value)}
              className={`min-h-9 cursor-pointer rounded-md px-3 text-xs font-bold transition duration-200 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#B8944F] sm:min-h-8 ${
                selected
                  ? "bg-[#0F2A43] text-white shadow-sm"
                  : "text-[#465867] hover:bg-white hover:text-[#0F2A43]"
              }`}
            >
              {labels[option.labelKey]}
            </button>
          );
        })}
      </div>
    </div>
  );
}
