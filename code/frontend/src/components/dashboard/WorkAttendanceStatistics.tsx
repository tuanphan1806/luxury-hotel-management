"use client";

import { useMemo } from "react";
import {
  formatWorkedMinutes,
  summarizeAttendance,
  summarizeAttendanceByDay,
  summarizeAttendanceByEmployee,
  type AttendanceSummary,
} from "@/lib/work-attendance-statistics";
import {
  formatWorkDateTime,
  workScheduleDisplayStatus,
  workScheduleTone,
  type WorkSchedule,
} from "@/lib/work-schedules";

interface WorkAttendanceStatisticsProps {
  schedules: WorkSchedule[];
  isAdmin: boolean;
  periodLabel: string;
  now: Date;
}

const HOTEL_TIME_ZONE = "Asia/Ho_Chi_Minh";
const attendanceDateFormatter = new Intl.DateTimeFormat("vi-VN", {
  weekday: "long",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: HOTEL_TIME_ZONE,
});
const attendanceTimeFormatter = new Intl.DateTimeFormat("vi-VN", {
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: HOTEL_TIME_ZONE,
});

const toneClass = {
  active: "border-emerald-200 bg-emerald-50 text-emerald-800",
  danger: "border-rose-200 bg-rose-50 text-rose-800",
  muted: "border-slate-200 bg-slate-50 text-slate-600",
  success: "border-blue-200 bg-blue-50 text-blue-800",
  scheduled: "border-amber-200 bg-amber-50 text-amber-900",
};

const formatDate = (value: string) => attendanceDateFormatter.format(new Date(`${value}T12:00:00+07:00`));

const formatTime = (value: string) => attendanceTimeFormatter.format(new Date(value));

function RateBar({ summary }: { summary: AttendanceSummary }) {
  return (
    <div className="flex items-center gap-3">
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-[#0F2A43]/10" aria-hidden="true">
        <span
          className="block h-full rounded-full bg-emerald-500 transition-[width] duration-300"
          style={{ width: `${Math.min(100, summary.attendanceRate)}%` }}
        />
      </div>
      <strong className="w-11 text-right text-xs tabular-nums text-[#0F2A43]">{summary.attendanceRate}%</strong>
    </div>
  );
}

function StatCell({ label, value, tone = "navy" }: { label: string; value: string | number; tone?: "navy" | "green" | "orange" | "red" }) {
  const styles = {
    navy: "bg-[#F1F0EA] text-[#0F2A43]",
    green: "bg-emerald-50 text-emerald-900",
    orange: "bg-orange-50 text-orange-900",
    red: "bg-rose-50 text-rose-900",
  }[tone];
  return (
    <div className={`rounded-xl px-4 py-3 ${styles}`}>
      <p className="text-[9px] font-black uppercase tracking-[0.14em] opacity-65">{label}</p>
      <p className="mt-1 text-xl font-black tabular-nums">{value}</p>
    </div>
  );
}

function InitialBadge({ name }: { name: string }) {
  return (
    <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-[#0F2A43] text-sm font-black text-white" aria-hidden="true">
      {name.trim().charAt(0).toUpperCase() || "N"}
    </span>
  );
}

export default function WorkAttendanceStatistics({ schedules, isAdmin, periodLabel, now }: WorkAttendanceStatisticsProps) {
  const summary = useMemo(() => summarizeAttendance(schedules, now), [now, schedules]);
  const employees = useMemo(() => summarizeAttendanceByEmployee(schedules, now), [now, schedules]);
  const days = useMemo(() => summarizeAttendanceByDay(schedules, now), [now, schedules]);
  const concludedShifts = summary.attendedShifts + summary.absentShifts + summary.unrecordedShifts;
  const attentionCount = summary.absentShifts + summary.unrecordedShifts;

  if (schedules.length === 0) {
    return (
      <section className="rounded-2xl border border-dashed border-[#0F2A43]/20 bg-white px-6 py-14 text-center">
        <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-[#0F2A43]/6 text-xl" aria-hidden="true">◷</span>
        <h2 className="mt-4 font-serif text-2xl font-bold text-[#0F2A43]">Chưa có dữ liệu chấm công</h2>
        <p className="mx-auto mt-2 max-w-xl text-sm leading-6 text-[#66727C]">Chưa có ca làm việc nào trong {periodLabel.toLowerCase()}. Hãy chọn kỳ khác hoặc phân ca mới.</p>
      </section>
    );
  }

  return (
    <div className="space-y-5">
      <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="attendance-overview-title">
        <div className="grid lg:grid-cols-[minmax(18rem,0.8fr)_minmax(0,1.2fr)]">
          <div className="bg-[#0F2A43] px-5 py-5 text-white sm:px-6 sm:py-6">
            <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#D8C398]">Tỷ lệ đi làm</p>
            <div className="mt-2 flex items-end gap-3">
              <strong className="text-5xl font-black tabular-nums">{summary.attendanceRate}%</strong>
              <span className="pb-1 text-xs leading-5 text-white/65">trên {concludedShifts} ca<br />đã đến hạn</span>
            </div>
            <div className="mt-5 h-2.5 overflow-hidden rounded-full bg-white/15" aria-hidden="true">
              <span className="block h-full rounded-full bg-[#D8C398] transition-[width] duration-300" style={{ width: `${Math.min(100, summary.attendanceRate)}%` }} />
            </div>
            <p id="attendance-overview-title" className="mt-4 text-xs leading-5 text-white/70">{periodLabel}</p>
            <div className="mt-4 grid grid-cols-2 gap-2 border-t border-white/10 pt-4 text-xs">
              <span className="text-white/60">Tổng giờ thực tế</span><strong className="text-right tabular-nums">{formatWorkedMinutes(summary.workedMinutes)}</strong>
              <span className="text-white/60">Tổng ca trong kỳ</span><strong className="text-right tabular-nums">{summary.totalShifts}</strong>
            </div>
          </div>
          <div className="grid content-center gap-3 p-4 sm:grid-cols-2 sm:p-5">
            <StatCell label="Đúng giờ" value={summary.onTimeShifts} tone="green" />
            <StatCell label="Đi muộn" value={summary.lateShifts} tone="orange" />
            <StatCell label="Vắng mặt" value={summary.absentShifts} tone="red" />
            <StatCell label="Quá hạn chưa chấm" value={summary.unrecordedShifts} tone={summary.unrecordedShifts > 0 ? "red" : "navy"} />
          </div>
        </div>
        <div className={`grid gap-3 border-t px-5 py-4 text-xs sm:grid-cols-[1fr_auto] sm:items-center ${attentionCount > 0 ? "border-orange-200 bg-orange-50" : "border-emerald-200 bg-emerald-50"}`}>
          <div>
            <strong className={attentionCount > 0 ? "text-orange-900" : "text-emerald-900"}>{attentionCount > 0 ? `${attentionCount} ca cần quản lý kiểm tra` : "Không có ca bất thường trong kỳ"}</strong>
            <p className={`mt-1 leading-5 ${attentionCount > 0 ? "text-orange-800/75" : "text-emerald-800/75"}`}>{attentionCount > 0 ? "Ưu tiên các ca vắng mặt hoặc đã qua giờ kết thúc nhưng chưa có chấm công." : "Dữ liệu chấm công đã được ghi nhận đầy đủ cho các ca đến hạn."}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <span className="rounded-full bg-white/75 px-3 py-1.5 font-bold text-[#0F2A43]">{summary.upcomingShifts} ca sắp tới</span>
            <span className="rounded-full bg-white/75 px-3 py-1.5 font-bold text-amber-800">{summary.awaitingCheckInShifts} chờ check-in</span>
          </div>
        </div>
      </section>

      {isAdmin && (
        <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="employee-attendance-title">
          <header className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-5 py-4 sm:flex-row sm:items-end sm:justify-between">
            <div>
              <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#9A7531]">Theo nhân viên</p>
              <h2 id="employee-attendance-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Kết quả trong kỳ</h2>
            </div>
            <p className="text-xs text-[#66727C]">{employees.length} nhân viên có lịch</p>
          </header>
          <div className="divide-y divide-[#0F2A43]/8">
            {employees.map((employee) => {
              const employeeAttention = employee.absentShifts + employee.unrecordedShifts;
              return (
                <article key={employee.employeeId} className="grid gap-4 px-4 py-4 transition duration-200 hover:bg-[#FBFAF6] md:px-5 lg:grid-cols-[minmax(13rem,1fr)_minmax(20rem,1.4fr)_minmax(12rem,0.75fr)] lg:items-center">
                  <div className="flex min-w-0 items-center gap-3">
                    <InitialBadge name={employee.employeeName} />
                    <div className="min-w-0">
                      <strong className="block truncate text-sm text-[#0F2A43]">{employee.employeeName}</strong>
                      <span className={`mt-1 inline-flex rounded-full px-2 py-0.5 text-[9px] font-bold ${employeeAttention > 0 ? "bg-orange-50 text-orange-800" : "bg-emerald-50 text-emerald-800"}`}>{employeeAttention > 0 ? `${employeeAttention} ca cần xem` : "Không bất thường"}</span>
                    </div>
                  </div>
                  <dl className="grid grid-cols-2 gap-2 sm:grid-cols-5">
                    {[
                      ["Tổng ca", employee.totalShifts, "text-[#0F2A43]"],
                      ["Đúng giờ", employee.onTimeShifts, "text-emerald-700"],
                      ["Đi muộn", employee.lateShifts, "text-orange-700"],
                      ["Vắng", employee.absentShifts, "text-rose-700"],
                      ["Giờ thực tế", formatWorkedMinutes(employee.workedMinutes), "text-[#0F2A43]"],
                    ].map(([label, value, tone]) => (
                      <div key={String(label)} className="rounded-lg bg-[#F1F0EA]/65 px-3 py-2">
                        <dt className="text-[9px] font-black uppercase tracking-wide text-[#66727C]">{label}</dt>
                        <dd className={`mt-1 text-xs font-bold tabular-nums ${tone}`}>{value}</dd>
                      </div>
                    ))}
                  </dl>
                  <div>
                    <p className="mb-2 text-[9px] font-black uppercase tracking-wide text-[#66727C]">Tỷ lệ đi làm</p>
                    <RateBar summary={employee} />
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      )}

      <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="daily-attendance-title">
        <header className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-5 py-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#9A7531]">Nhật ký theo ngày</p>
            <h2 id="daily-attendance-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Giờ vào, giờ ra và trạng thái</h2>
          </div>
          <p className="text-xs text-[#66727C]">Bấm một ngày để xem chi tiết</p>
        </header>
        <div className="space-y-2 bg-[#F4F1E9]/55 p-3">
          {days.map((day, index) => (
            <details key={day.workDate} className="group overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white [content-visibility:auto]" open={index === 0}>
              <summary className="grid min-h-16 cursor-pointer list-none grid-cols-[minmax(0,1fr)_auto] items-center gap-3 px-4 py-3 transition duration-200 hover:bg-[#F8F4EA] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] lg:grid-cols-[minmax(14rem,1fr)_repeat(4,minmax(5.5rem,auto))_auto]">
                <div>
                  <strong className="block capitalize text-sm text-[#0F2A43]">{formatDate(day.workDate)}</strong>
                  <span className="mt-1 block text-[10px] text-[#66727C]">{day.totalShifts} ca · {formatWorkedMinutes(day.workedMinutes)}</span>
                </div>
                <span className="hidden text-xs lg:block"><b className="text-emerald-700">{day.onTimeShifts}</b> đúng giờ</span>
                <span className="hidden text-xs lg:block"><b className="text-orange-700">{day.lateShifts}</b> đi muộn</span>
                <span className="hidden text-xs lg:block"><b className="text-rose-700">{day.absentShifts}</b> vắng</span>
                <span className="hidden text-xs lg:block"><b className="text-[#0F2A43]">{day.unrecordedShifts}</b> chưa ghi nhận</span>
                <span className="flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/15 text-[#0F2A43] transition duration-200 group-open:rotate-180" aria-hidden="true"><svg viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg></span>
              </summary>
              <div className="divide-y divide-[#0F2A43]/8 border-t border-[#0F2A43]/10">
                {day.schedules.map((schedule) => (
                  <article key={schedule.id} className="grid gap-3 px-4 py-3 transition duration-200 hover:bg-[#FBFAF6] md:grid-cols-[1.1fr_1fr_1fr] md:items-center">
                    <div className="flex min-w-0 items-start gap-3">
                      <span className="mt-0.5 h-10 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} aria-hidden="true" />
                      <div className="min-w-0">
                        <strong className="block truncate text-sm text-[#0F2A43]">{schedule.shiftName}</strong>
                        <span className="mt-1 block truncate text-[11px] text-[#66727C]">{isAdmin ? `${schedule.employeeName} · ` : ""}{formatTime(schedule.scheduledStartUtc)}–{formatTime(schedule.scheduledEndUtc)}</span>
                      </div>
                    </div>
                    <div className="grid grid-cols-2 gap-2 text-[11px] leading-5 text-[#66727C]">
                      <span>Vào: <b className="text-[#27445F]">{formatWorkDateTime(schedule.actualCheckInUtc)}</b></span>
                      <span>Ra: <b className="text-[#27445F]">{formatWorkDateTime(schedule.actualCheckOutUtc)}</b></span>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 md:justify-end">
                      <span className={`rounded-full border px-2.5 py-1 text-[10px] font-bold ${toneClass[workScheduleTone(schedule)]}`}>{workScheduleDisplayStatus(schedule)}</span>
                      {schedule.late && <span className="text-[10px] font-bold text-orange-700">Muộn {schedule.lateMinutes} phút</span>}
                      {schedule.autoCheckOut && <span className="text-[10px] font-bold text-slate-600">Tự động kết thúc</span>}
                    </div>
                  </article>
                ))}
              </div>
            </details>
          ))}
        </div>
      </section>
    </div>
  );
}
