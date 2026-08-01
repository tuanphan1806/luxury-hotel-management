"use client";

import { useMemo } from "react";
import {
  formatWorkDateTime,
  groupWorkSchedulesByDate,
  workScheduleDisplayStatus,
  workScheduleTone,
  type WorkSchedule,
} from "@/lib/work-schedules";

interface WorkScheduleListViewProps {
  schedules: WorkSchedule[];
  isAdmin: boolean;
  isStaff: boolean;
  periodLabel: string;
  now: Date;
  onEdit: (schedule: WorkSchedule) => void;
  onCancel: (schedule: WorkSchedule) => void;
  onCheckout: (schedule: WorkSchedule) => void;
}

const HOTEL_TIME_ZONE = "Asia/Ho_Chi_Minh";
const workDateFormatter = new Intl.DateTimeFormat("vi-VN", {
  weekday: "long",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: HOTEL_TIME_ZONE,
});
const workTimeFormatter = new Intl.DateTimeFormat("vi-VN", {
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: HOTEL_TIME_ZONE,
});
const workDateKeyFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: HOTEL_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

const toneClass: Record<ReturnType<typeof workScheduleTone>, string> = {
  active: "border-emerald-200 bg-emerald-50 text-emerald-800",
  danger: "border-rose-200 bg-rose-50 text-rose-800",
  muted: "border-slate-200 bg-slate-50 text-slate-600",
  success: "border-blue-200 bg-blue-50 text-blue-800",
  scheduled: "border-amber-200 bg-amber-50 text-amber-900",
};

const formatDate = (value: string) => workDateFormatter.format(new Date(`${value}T12:00:00+07:00`));

const formatTime = (value: string) => workTimeFormatter.format(new Date(value));

function StatusBadge({ schedule }: { schedule: WorkSchedule }) {
  return (
    <span className={`inline-flex min-h-7 items-center rounded-full border px-2.5 text-[10px] font-bold ${toneClass[workScheduleTone(schedule)]}`}>
      {workScheduleDisplayStatus(schedule)}
    </span>
  );
}

function Metric({ label, value, tone }: { label: string; value: number; tone: "navy" | "green" | "orange" | "red" }) {
  const styles = {
    navy: "border-[#0F2A43]/10 bg-white text-[#0F2A43]",
    green: "border-emerald-200 bg-emerald-50 text-emerald-900",
    orange: "border-orange-200 bg-orange-50 text-orange-900",
    red: "border-rose-200 bg-rose-50 text-rose-900",
  }[tone];
  return (
    <div className={`rounded-xl border px-4 py-3 ${styles}`}>
      <p className="text-[9px] font-black uppercase tracking-[0.14em] opacity-65">{label}</p>
      <p className="mt-1 text-xl font-black tabular-nums">{value}</p>
    </div>
  );
}

export default function WorkScheduleListView({
  schedules,
  isAdmin,
  isStaff,
  periodLabel,
  now,
  onEdit,
  onCancel,
  onCheckout,
}: WorkScheduleListViewProps) {
  const today = workDateKeyFormatter.format(now);
  const groupedSchedules = useMemo(() => groupWorkSchedulesByDate(schedules), [schedules]);
  const sortedDates = useMemo(() => {
    const dates = Object.keys(groupedSchedules);
    const hasCurrentOrFutureDate = dates.some((day) => day >= today);
    return dates.sort((left, right) => hasCurrentOrFutureDate
      ? left.localeCompare(right)
      : right.localeCompare(left));
  }, [groupedSchedules, today]);
  const summary = useMemo(() => schedules.reduce((result, item) => {
    result.total += 1;
    if (item.sessionStatus === "ACTIVE") result.active += 1;
    if (item.status === "FULFILLED") result.completed += 1;
    if (item.late) result.late += 1;
    if (item.status === "ABSENT") result.absent += 1;
    if (item.status === "SCHEDULED"
      && !item.sessionId
      && new Date(item.scheduledEndUtc).getTime() > now.getTime()) result.upcoming += 1;
    return result;
  }, { total: 0, active: 0, completed: 0, late: 0, absent: 0, upcoming: 0 }), [now, schedules]);

  if (schedules.length === 0) {
    return (
      <section className="rounded-2xl border border-dashed border-[#0F2A43]/20 bg-white px-6 py-14 text-center">
        <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-[#0F2A43]/6 text-xl" aria-hidden="true">◷</span>
        <h2 className="mt-4 font-serif text-2xl font-bold text-[#0F2A43]">Chưa có ca phù hợp</h2>
        <p className="mx-auto mt-2 max-w-xl text-sm leading-6 text-[#66727C]">Không có ca nào trong {periodLabel.toLowerCase()} với bộ lọc hiện tại.</p>
      </section>
    );
  }

  return (
    <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="schedule-list-title">
      <header className="border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-4 py-4 sm:px-5">
        <div className="flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#9A7531]">Theo ngày làm việc</p>
            <h2 id="schedule-list-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{isAdmin ? "Danh sách phân ca" : "Ca của tôi"}</h2>
            <p className="mt-1 text-xs leading-5 text-[#66727C]">{periodLabel} · {sortedDates.length} ngày có lịch</p>
          </div>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4" aria-label="Tóm tắt danh sách ca">
            <Metric label="Tổng ca" value={summary.total} tone="navy" />
            <Metric label={isAdmin ? "Đang làm" : "Sắp tới"} value={isAdmin ? summary.active : summary.upcoming} tone="green" />
            <Metric label={isAdmin ? "Đi muộn" : "Hoàn thành"} value={isAdmin ? summary.late : summary.completed} tone="orange" />
            <Metric label="Vắng mặt" value={summary.absent} tone="red" />
          </div>
        </div>
      </header>

      <div className="space-y-3 bg-[#F4F1E9]/55 p-3 sm:p-4">
        {sortedDates.map((day, index) => {
          const daySchedules = groupedSchedules[day];
          const daySummary = daySchedules.reduce((result, item) => {
            if (item.sessionStatus === "ACTIVE") result.active += 1;
            if (item.status === "FULFILLED") result.completed += 1;
            if (item.late || item.status === "ABSENT") result.attention += 1;
            return result;
          }, { active: 0, completed: 0, attention: 0 });
          return (
            <details
              key={day}
              className="group overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white [content-visibility:auto]"
              open={day === today || index === 0}
            >
              <summary className="grid min-h-16 cursor-pointer list-none grid-cols-[minmax(0,1fr)_auto] items-center gap-3 px-4 py-3 transition duration-200 hover:bg-[#F8F4EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] lg:grid-cols-[minmax(15rem,1fr)_auto_auto_auto_auto]">
                <div className="min-w-0">
                  <strong className="block truncate capitalize text-sm text-[#0F2A43]">{formatDate(day)}</strong>
                  <span className="mt-1 block text-[10px] font-semibold text-[#66727C]">{daySchedules.length} ca được ghi nhận</span>
                </div>
                <span className="hidden text-[11px] text-[#66727C] lg:block"><b className="tabular-nums text-emerald-700">{daySummary.active}</b> đang làm</span>
                <span className="hidden text-[11px] text-[#66727C] lg:block"><b className="tabular-nums text-blue-700">{daySummary.completed}</b> hoàn thành</span>
                <span className={`hidden rounded-full px-2.5 py-1 text-[10px] font-bold lg:inline-flex ${daySummary.attention > 0 ? "bg-orange-50 text-orange-800" : "bg-emerald-50 text-emerald-800"}`}>
                  {daySummary.attention > 0 ? `${daySummary.attention} cần lưu ý` : "Không bất thường"}
                </span>
                <span className="flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/15 text-[#0F2A43] transition duration-200 group-open:rotate-180" aria-hidden="true">
                  <svg viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
                </span>
              </summary>

              <div className="border-t border-[#0F2A43]/10">
                <div className={`hidden border-b border-[#0F2A43]/8 bg-[#F8F7F2] px-4 py-2 text-[9px] font-black uppercase tracking-[0.12em] text-[#66727C] lg:grid ${isAdmin ? "grid-cols-[1.05fr_0.8fr_1fr_1fr_auto]" : "grid-cols-[1.2fr_1fr_1fr_auto]"}`}>
                  <span>Ca làm việc</span>
                  {isAdmin && <span>Nhân viên</span>}
                  <span>Điểm danh</span>
                  <span>Trạng thái</span>
                  <span className="text-right">Thao tác</span>
                </div>
                <div className="divide-y divide-[#0F2A43]/8">
                  {daySchedules.map((schedule) => (
                    <article
                      key={schedule.id}
                      className={`grid gap-3 px-4 py-3 transition duration-200 hover:bg-[#FBFAF6] lg:items-center ${isAdmin ? "lg:grid-cols-[1.05fr_0.8fr_1fr_1fr_auto]" : "lg:grid-cols-[1.2fr_1fr_1fr_auto]"}`}
                    >
                      <div className="flex min-w-0 items-center gap-3">
                        <span className="h-10 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} aria-hidden="true" />
                        <div className="min-w-0">
                          <strong className="block truncate text-sm text-[#0F2A43]">{schedule.shiftName}</strong>
                          <span className="mt-1 block text-[11px] font-semibold text-[#66727C]">{formatTime(schedule.scheduledStartUtc)}–{formatTime(schedule.scheduledEndUtc)} · {schedule.shiftCode}</span>
                        </div>
                      </div>
                      {isAdmin && (
                        <div className="min-w-0">
                          <span className="block truncate text-xs font-bold text-[#27445F]">{schedule.employeeName}</span>
                          <span className="mt-1 block text-[10px] text-[#66727C]">Nhân viên được phân công</span>
                        </div>
                      )}
                      <dl className="grid grid-cols-2 gap-2 text-[11px] lg:block lg:leading-5">
                        <div><dt className="inline text-[#66727C]">Vào: </dt><dd className="inline font-semibold text-[#27445F]">{formatWorkDateTime(schedule.actualCheckInUtc)}</dd></div>
                        <div><dt className="inline text-[#66727C]">Ra: </dt><dd className="inline font-semibold text-[#27445F]">{formatWorkDateTime(schedule.actualCheckOutUtc)}</dd></div>
                      </dl>
                      <div className="flex flex-wrap items-center gap-2">
                        <StatusBadge schedule={schedule} />
                        {schedule.late && <span className="text-[10px] font-bold text-orange-700">Muộn {schedule.lateMinutes} phút</span>}
                        {schedule.autoCheckOut && <span className="text-[10px] font-bold text-slate-600">Tự động kết thúc</span>}
                      </div>
                      <div className="flex flex-wrap gap-2 lg:justify-end">
                        {isAdmin && schedule.status === "SCHEDULED" && !schedule.sessionId && (
                          <>
                            <button type="button" aria-label={`Sửa ${schedule.shiftName} ngày ${formatDate(day)}`} onClick={() => onEdit(schedule)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-[#F8F4EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">Sửa</button>
                            <button type="button" aria-label={`Hủy ${schedule.shiftName} ngày ${formatDate(day)}`} onClick={() => onCancel(schedule)} className="min-h-11 rounded-lg border border-rose-200 px-3 text-xs font-bold text-rose-700 transition hover:bg-rose-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-400">Hủy</button>
                          </>
                        )}
                        {isStaff && schedule.sessionStatus === "ACTIVE" && (
                          <button type="button" aria-label={`Check-out ${schedule.shiftName} ngày ${formatDate(day)}`} onClick={() => onCheckout(schedule)} className="min-h-11 rounded-lg bg-[#B8944F] px-3 text-xs font-bold text-[#0F2A43] transition hover:bg-[#C7A865] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#0F2A43]">Check-out</button>
                        )}
                      </div>
                    </article>
                  ))}
                </div>
              </div>
            </details>
          );
        })}
      </div>
    </section>
  );
}
