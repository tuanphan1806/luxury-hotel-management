"use client";

import React from "react";

interface DashboardFilterPanelProps {
  title: string;
  description?: string;
  children: React.ReactNode;
  actions?: React.ReactNode;
  resultCount: number;
  resultLabel: string;
  resultNote?: string;
  hasActiveFilters?: boolean;
  activeFilterCount?: number;
  activeFilterLabel?: string;
  onReset?: () => void;
  resetLabel?: string;
  className?: string;
}

interface DashboardSearchFieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  clearLabel?: string;
  className?: string;
}

interface DashboardSelectFieldProps extends Omit<React.SelectHTMLAttributes<HTMLSelectElement>, "className"> {
  id: string;
  label: string;
  children: React.ReactNode;
  className?: string;
}

export function DashboardFilterPanel({
  title,
  description,
  children,
  actions,
  resultCount,
  resultLabel,
  resultNote,
  hasActiveFilters = false,
  activeFilterCount = 0,
  activeFilterLabel = "bộ lọc đang dùng",
  onReset,
  resetLabel = "Xóa bộ lọc",
  className = "",
}: DashboardFilterPanelProps) {
  return (
    <section className={`ops-panel overflow-hidden rounded-xl border ${className}`} aria-label={title}>
      <header className="ops-section-header flex flex-col gap-3 px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-5">
        <div className="min-w-0">
          <h2 className="text-base font-bold leading-6">{title}</h2>
          {description && <p className="mt-0.5 text-xs leading-5 text-white/70">{description}</p>}
        </div>
        {actions && <div className="flex min-h-11 flex-wrap items-center gap-2 sm:justify-end">{actions}</div>}
      </header>

      <div className="grid gap-4 p-4 sm:p-5">{children}</div>

      <footer className="ops-panel-muted flex min-h-12 flex-col gap-2 border-t px-4 py-3 text-xs text-[#66727C] sm:flex-row sm:items-center sm:justify-between sm:px-5">
        <div className="flex flex-wrap items-center gap-2" aria-live="polite">
          <p>
            <strong className="font-bold tabular-nums text-[#80632F]">{resultCount}</strong> {resultLabel}
            {resultNote && <span className="text-[#9A928A]"> · {resultNote}</span>}
          </p>
          {activeFilterCount > 0 && (
            <span className="inline-flex min-h-6 items-center rounded-md bg-[var(--ops-accent-soft)] px-2 font-bold text-[#0F2A43]">
              {activeFilterCount} {activeFilterLabel}
            </span>
          )}
        </div>
        {hasActiveFilters && onReset && (
          <button
            type="button"
            onClick={onReset}
            className="inline-flex min-h-11 items-center self-start rounded-lg px-2 text-xs font-bold text-[#0F2A43] underline-offset-4 transition hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] sm:min-h-8 sm:self-auto"
          >
            {resetLabel}
          </button>
        )}
      </footer>
    </section>
  );
}

export function DashboardSearchField({
  id,
  label,
  value,
  onChange,
  placeholder,
  clearLabel = "Xóa từ khóa",
  className = "",
}: DashboardSearchFieldProps) {
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-2 block text-xs font-bold text-[#66727C]">{label}</label>
      <div className="relative">
        <svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-[#66727C]">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
        <input
          id={id}
          type="search"
          value={value}
          onChange={(event) => onChange(event.target.value)}
          placeholder={placeholder}
          className="ops-control min-h-11 w-full rounded-lg border py-2.5 pl-10 pr-11 text-sm text-[#0F2A43] outline-none transition placeholder:text-[#66727C] hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20 [&::-webkit-search-cancel-button]:appearance-none"
        />
        {value && (
          <button
            type="button"
            onClick={() => onChange("")}
            aria-label={clearLabel}
            className="absolute right-1 top-1/2 inline-flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded-md text-lg leading-none text-[#66727C] transition hover:bg-[var(--ops-surface-muted)] hover:text-[#0F2A43] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
          >
            ×
          </button>
        )}
      </div>
    </div>
  );
}

export function DashboardSelectField({ id, label, children, className = "", ...props }: DashboardSelectFieldProps) {
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-2 block text-xs font-bold text-[#66727C]">{label}</label>
      <select
        id={id}
        {...props}
        className="ops-control min-h-11 w-full rounded-lg border px-3 py-2.5 text-sm font-semibold text-[#27445F] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
      >
        {children}
      </select>
    </div>
  );
}

export function FilterQuickButton({ active = false, className = "", ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { active?: boolean }) {
  return (
    <button
      type="button"
      {...props}
      aria-pressed={active}
      className={`inline-flex min-h-11 items-center justify-center rounded-lg border px-3 text-xs font-bold transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] ${
        active
          ? "border-[#B8944F] bg-[#B8944F] text-[#0F2A43]"
          : "border-white/20 bg-white/5 text-white hover:border-white/35 hover:bg-white/10"
      } ${className}`}
    />
  );
}
