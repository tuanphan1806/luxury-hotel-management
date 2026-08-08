"use client";

import type { Dispatch, FormEventHandler, SetStateAction } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import {
  formatShiftTime,
  workShiftColorForStartTime,
  workShiftPeriodFromStartTime,
  type WorkShiftTemplate,
  type WorkShiftTemplateForm,
} from "@/lib/work-schedules";

type Props = {
  open: boolean;
  busy: boolean;
  templates: WorkShiftTemplate[];
  editing: WorkShiftTemplate | null;
  form: WorkShiftTemplateForm;
  error: string;
  onClose: () => void;
  onNew: () => void;
  onSelect: (template: WorkShiftTemplate) => void;
  setForm: Dispatch<SetStateAction<WorkShiftTemplateForm>>;
  onSubmit: FormEventHandler<HTMLFormElement>;
};

const inputClass = "ops-control min-h-11 w-full rounded-lg border px-3 py-2.5 text-sm font-semibold text-[#0F2A43] outline-none transition hover:border-[#0F2A43]/30 focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20 disabled:cursor-not-allowed disabled:opacity-60";
const labelClass = "mb-2 block text-xs font-bold text-[#66727C]";

const periodLabels = {
  MORNING: "Ca sáng",
  AFTERNOON: "Ca chiều",
  NIGHT: "Ca tối",
} as const;

export default function WorkShiftTemplateManagerModal({
  open,
  busy,
  templates,
  editing,
  form,
  error,
  onClose,
  onNew,
  onSelect,
  setForm,
  onSubmit,
}: Props) {
  const period = workShiftPeriodFromStartTime(form.startTime);
  const automaticColor = workShiftColorForStartTime(form.startTime);

  return (
    <ViewportModal
      open={open}
      onClose={onClose}
      labelledBy="template-manager-title"
      busy={busy}
      panelClassName="max-w-5xl"
    >
      <div className="flex min-h-0 flex-1 flex-col">
        <header className="flex flex-wrap items-start justify-between gap-3 border-b px-5 py-4">
          <div>
            <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">
              Preset dùng lại khi xếp lịch
            </p>
            <h2 id="template-manager-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">
              Thiết lập mẫu ca
            </h2>
            <p className="mt-2 max-w-2xl text-xs leading-5 text-[#66727C]">
              Mẫu ca cung cấp giờ và chính sách mặc định cho ca tạo mới hoặc tạo hàng loạt. Thay đổi mẫu không sửa các ca đã tạo trước đó.
            </p>
          </div>
          <button
            type="button"
            onClick={onNew}
            className="min-h-10 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F8F4EA]"
          >
            + Mẫu mới
          </button>
        </header>

        <div className="lux-scrollbar grid min-h-0 flex-1 gap-5 overflow-y-auto p-5 lg:grid-cols-[0.85fr_1.15fr]">
          <div>
            <p className="mb-2 text-[10px] font-bold uppercase tracking-[0.14em] text-[#80632F]">
              Mẫu đang có
            </p>
            <div className="space-y-2">
              {templates.length === 0 && (
                <div className="rounded-xl border border-dashed border-[#0F2A43]/15 bg-white p-5 text-sm text-[#66727C]">
                  Chưa có mẫu ca. Tạo mẫu đầu tiên để dùng khi mở ca theo ngày.
                </div>
              )}
              {templates.map((template) => (
                <button
                  type="button"
                  key={template.id}
                  onClick={() => onSelect(template)}
                  className={`flex min-h-16 w-full items-center gap-3 rounded-xl border p-3 text-left transition hover:border-[#B8944F] ${
                    editing?.id === template.id
                      ? "border-[#B8944F] bg-[#F8F4EA]"
                      : "border-[#0F2A43]/10 bg-white"
                  }`}
                >
                  <span className="h-9 w-2 rounded-full" style={{ backgroundColor: template.color }} />
                  <span className="min-w-0 flex-1">
                    <strong className="block truncate text-sm text-[#0F2A43]">{template.name}</strong>
                    <span className="mt-1 block text-xs text-[#66727C]">
                      {formatShiftTime(template.startTime)}–{formatShiftTime(template.endTime)}
                      {template.crossesMidnight ? " · qua ngày" : ""}
                    </span>
                  </span>
                  <span className={`rounded-full px-2 py-1 text-[9px] font-bold ${
                    template.active ? "bg-emerald-50 text-emerald-700" : "bg-slate-100 text-slate-600"
                  }`}>
                    {template.active ? "Đang dùng" : "Đã dừng"}
                  </span>
                </button>
              ))}
            </div>
          </div>

          <form onSubmit={onSubmit} className="grid content-start gap-4 rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4 sm:grid-cols-2">
            <div className="sm:col-span-2 flex items-center gap-3 rounded-xl border border-[#0F2A43]/10 bg-white p-3">
              <span className="h-11 w-3 rounded-full" style={{ backgroundColor: automaticColor }} aria-hidden="true" />
              <span>
                <span className="block text-[10px] font-bold uppercase tracking-[0.12em] text-[#80632F]">Nhận diện tự động</span>
                <strong className="mt-1 block text-sm text-[#0F2A43]">{periodLabels[period]}</strong>
                <span className="mt-0.5 block text-xs text-[#66727C]">Màu và thứ tự được khóa theo giờ bắt đầu để lịch luôn nhất quán.</span>
              </span>
            </div>

            <label>
              <span className={labelClass}>Mã ca *</span>
              <input
                data-modal-autofocus
                value={form.code}
                maxLength={32}
                onChange={(event) => setForm((current) => ({
                  ...current,
                  code: event.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, ""),
                }))}
                className={inputClass}
              />
            </label>
            <label>
              <span className={labelClass}>Tên ca *</span>
              <input
                value={form.name}
                maxLength={100}
                onChange={(event) => setForm((current) => ({ ...current, name: event.target.value }))}
                className={inputClass}
              />
            </label>
            <label>
              <span className={labelClass}>Bắt đầu *</span>
              <input
                type="time"
                value={form.startTime}
                onChange={(event) => setForm((current) => ({ ...current, startTime: event.target.value }))}
                className={inputClass}
              />
            </label>
            <label>
              <span className={labelClass}>Kết thúc *</span>
              <input
                type="time"
                value={form.endTime}
                onChange={(event) => setForm((current) => ({ ...current, endTime: event.target.value }))}
                className={inputClass}
              />
            </label>
            <label>
              <span className={labelClass}>Cho check-in sớm (phút)</span>
              <input
                type="number"
                min={0}
                max={240}
                value={form.checkInEarlyMinutes}
                onChange={(event) => setForm((current) => ({ ...current, checkInEarlyMinutes: Number(event.target.value) }))}
                className={inputClass}
              />
            </label>
            <label>
              <span className={labelClass}>Ngưỡng đi muộn (phút)</span>
              <input
                type="number"
                min={0}
                max={240}
                value={form.lateToleranceMinutes}
                onChange={(event) => setForm((current) => ({ ...current, lateToleranceMinutes: Number(event.target.value) }))}
                className={inputClass}
              />
            </label>
            <label className="flex min-h-11 items-center gap-3 sm:col-span-2">
              <input
                type="checkbox"
                checked={form.active}
                onChange={(event) => setForm((current) => ({ ...current, active: event.target.checked }))}
                className="h-5 w-5 accent-[#0F2A43]"
              />
              <span className="text-sm font-bold text-[#0F2A43]">Cho phép dùng mẫu này khi tạo ca mới</span>
            </label>

            {error && (
              <p role="alert" className="sm:col-span-2 rounded-lg border border-rose-200 bg-rose-50 p-3 text-xs font-semibold text-rose-700">
                {error}
              </p>
            )}

            <div className="flex justify-end sm:col-span-2">
              <button
                type="submit"
                disabled={busy}
                className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] disabled:opacity-60"
              >
                {busy ? "Đang lưu..." : editing ? "Cập nhật mẫu" : "Tạo mẫu ca"}
              </button>
            </div>
          </form>
        </div>

        <footer className="flex justify-end border-t px-5 py-4">
          <button type="button" onClick={onClose} className="min-h-11 rounded-lg border px-4 text-sm font-bold">
            Đóng
          </button>
        </footer>
      </div>
    </ViewportModal>
  );
}
