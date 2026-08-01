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

const toneClass = {
  active: "border-emerald-200 bg-emerald-50 text-emerald-800",
  danger: "border-rose-200 bg-rose-50 text-rose-800",
  muted: "border-slate-200 bg-slate-50 text-slate-600",
  success: "border-blue-200 bg-blue-50 text-blue-800",
  scheduled: "border-amber-200 bg-amber-50 text-amber-900",
};

const formatDate = (value: string) => new Intl.DateTimeFormat("vi-VN", {
  weekday: "long",
  day: "2-digit",
  month: "2-digit",
  year: "numeric",
  timeZone: "Asia/Ho_Chi_Minh",
}).format(new Date(`${value}T12:00:00+07:00`));

const formatTime = (value: string) => new Intl.DateTimeFormat("vi-VN", {
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: "Asia/Ho_Chi_Minh",
}).format(new Date(value));

function MetricCard({
  label,
  value,
  detail,
  tone = "navy",
}: {
  label: string;
  value: string | number;
  detail: string;
  tone?: "navy" | "green" | "gold" | "red";
}) {
  const styles = {
    navy: "border-[#0F2A43]/12 bg-white text-[#0F2A43]",
    green: "border-emerald-200 bg-emerald-50 text-emerald-950",
    gold: "border-[#D9C28F] bg-[#F8F2E5] text-[#0F2A43]",
    red: "border-rose-200 bg-rose-50 text-rose-950",
  }[tone];
  return (
    <article className={`rounded-2xl border p-4 shadow-[0_6px_18px_rgba(15,42,67,0.04)] ${styles}`}>
      <p className="text-[10px] font-black uppercase tracking-[0.16em] opacity-65">{label}</p>
      <p className="mt-2 text-2xl font-black tabular-nums sm:text-3xl">{value}</p>
      <p className="mt-2 text-xs font-semibold leading-5 opacity-70">{detail}</p>
    </article>
  );
}

function AttendanceRate({ summary }: { summary: AttendanceSummary }) {
  return (
    <div className="flex items-center gap-3">
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-[#0F2A43]/10" aria-hidden="true">
        <span
          className="block h-full rounded-full bg-emerald-500 transition-[width] duration-300"
          style={{ width: `${Math.min(100, summary.attendanceRate)}%` }}
        />
      </div>
      <strong className="w-11 text-right text-xs tabular-nums text-[#0F2A43]">
        {summary.attendanceRate}%
      </strong>
    </div>
  );
}

export default function WorkAttendanceStatistics({
  schedules,
  isAdmin,
  periodLabel,
  now,
}: WorkAttendanceStatisticsProps) {
  const summary = useMemo(() => summarizeAttendance(schedules, now), [now, schedules]);
  const employees = useMemo(() => summarizeAttendanceByEmployee(schedules, now), [now, schedules]);
  const days = useMemo(() => summarizeAttendanceByDay(schedules, now), [now, schedules]);
  const attentionCount = summary.absentShifts + summary.unrecordedShifts;

  if (schedules.length === 0) {
    return (
      <section className="rounded-2xl border border-dashed border-[#0F2A43]/20 bg-white px-6 py-14 text-center">
        <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full bg-[#0F2A43]/6 text-xl" aria-hidden="true">◷</span>
        <h2 className="mt-4 font-serif text-2xl font-bold text-[#0F2A43]">Chưa có dữ liệu chấm công</h2>
        <p className="mx-auto mt-2 max-w-xl text-sm leading-6 text-[#66727C]">
          Chưa có ca làm việc nào trong {periodLabel.toLowerCase()}. Hãy chọn kỳ khác hoặc phân ca mới.
        </p>
      </section>
    );
  }

  return (
    <div className="space-y-5">
      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label="Tổng hợp chấm công">
        <MetricCard
          label="Tổng ca trong kỳ"
          value={summary.totalShifts}
          detail={`${summary.completedShifts} đã hoàn thành · ${summary.activeShifts} đang làm`}
        />
        <MetricCard
          label="Đã có mặt"
          value={summary.attendedShifts}
          detail={`${summary.onTimeShifts} đúng giờ · ${summary.lateShifts} đi muộn`}
          tone="green"
        />
        <MetricCard
          label="Giờ làm thực tế"
          value={formatWorkedMinutes(summary.workedMinutes)}
          detail={`Tỷ lệ đi làm ${summary.attendanceRate}% trong số ca đã đến hạn`}
          tone="gold"
        />
        <MetricCard
          label="Cần lưu ý"
          value={attentionCount}
          detail={`${summary.absentShifts} vắng · ${summary.unrecordedShifts} ca quá hạn chưa chấm công`}
          tone={attentionCount > 0 ? "red" : "navy"}
        />
      </section>

      <section className="grid gap-3 rounded-xl border border-[#0F2A43]/10 bg-white p-4 text-xs sm:grid-cols-3" aria-label="Trạng thái ca còn lại">
        <div>
          <span className="text-[#66727C]">Ca sắp tới</span>
          <strong className="ml-2 tabular-nums text-[#0F2A43]">{summary.upcomingShifts}</strong>
        </div>
        <div>
          <span className="text-[#66727C]">Đang chờ check-in</span>
          <strong className="ml-2 tabular-nums text-amber-800">{summary.awaitingCheckInShifts}</strong>
        </div>
        <div>
          <span className="text-[#66727C]">Lịch đã hủy</span>
          <strong className="ml-2 tabular-nums text-slate-700">{summary.cancelledShifts}</strong>
        </div>
      </section>

      {isAdmin && (
        <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="employee-attendance-title">
          <header className="border-b border-[#0F2A43]/10 px-5 py-4">
            <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#B8944F]">Theo nhân viên</p>
            <h2 id="employee-attendance-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Kết quả chấm công trong kỳ</h2>
            <p className="mt-1 text-xs leading-5 text-[#66727C]">Tỷ lệ đi làm chỉ tính các ca đã có mặt, đã vắng hoặc đã quá giờ kết thúc.</p>
          </header>
          <div className="divide-y divide-[#0F2A43]/8">
            {employees.map((employee) => (
              <article
                key={employee.employeeId}
                className="grid gap-4 px-4 py-4 transition hover:bg-[#FBFAF6] md:px-5 lg:grid-cols-[minmax(12rem,1.1fr)_minmax(20rem,1.4fr)_minmax(12rem,0.8fr)] lg:items-center"
              >
                <div>
                  <strong className="block text-sm text-[#0F2A43]">{employee.employeeName}</strong>
                  <span className="mt-1 block text-[10px] text-[#66727C]">{employee.onTimeShifts} ca đúng giờ</span>
                </div>
                <dl className="grid grid-cols-2 gap-2 sm:grid-cols-5">
                  {[
                    ["Tổng ca", employee.totalShifts, "text-[#0F2A43]"],
                    ["Có mặt", employee.attendedShifts, "text-emerald-700"],
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
                  <AttendanceRate summary={employee} />
                </div>
              </article>
            ))}
          </div>
        </section>
      )}

      <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="daily-attendance-title">
        <header className="border-b border-[#0F2A43]/10 px-5 py-4">
          <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#B8944F]">Theo ngày làm việc</p>
          <h2 id="daily-attendance-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Chi tiết từng ngày</h2>
          <p className="mt-1 text-xs leading-5 text-[#66727C]">Mở từng ngày để xem ca dự kiến và giờ chấm công thực tế.</p>
        </header>
        <div className="space-y-2 bg-[#F7F4EC]/60 p-3">
          {days.map((day) => (
            <details key={day.workDate} className="group overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white">
              <summary className="grid min-h-16 cursor-pointer list-none grid-cols-[minmax(0,1fr)_auto] items-center gap-3 px-4 py-3 transition hover:bg-[#F1F0EA]/65 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] lg:grid-cols-[minmax(14rem,1.3fr)_repeat(4,minmax(5rem,0.55fr))_auto]">
                <div>
                  <strong className="block capitalize text-sm text-[#0F2A43]">{formatDate(day.workDate)}</strong>
                  <span className="mt-1 block text-[10px] text-[#66727C]">{day.totalShifts} ca · {formatWorkedMinutes(day.workedMinutes)}</span>
                </div>
                <span className="hidden text-xs lg:block"><b className="text-emerald-700">{day.attendedShifts}</b> có mặt</span>
                <span className="hidden text-xs lg:block"><b className="text-orange-700">{day.lateShifts}</b> đi muộn</span>
                <span className="hidden text-xs lg:block"><b className="text-rose-700">{day.absentShifts}</b> vắng</span>
                <span className="hidden text-xs lg:block"><b className="text-[#0F2A43]">{day.unrecordedShifts}</b> chưa ghi nhận</span>
                <span className="flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/15 text-[#0F2A43] transition group-open:rotate-180" aria-hidden="true">
                  <svg viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
                </span>
              </summary>
              <div className="divide-y divide-[#0F2A43]/8 border-t border-[#0F2A43]/10 bg-[#FBFAF6]">
                {day.schedules.map((schedule) => (
                  <article key={schedule.id} className="grid gap-3 px-4 py-3 md:grid-cols-[1.1fr_1fr_1fr] md:items-center">
                    <div className="flex min-w-0 items-start gap-3">
                      <span className="mt-0.5 h-10 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} aria-hidden="true" />
                      <div className="min-w-0">
                        <strong className="block truncate text-sm text-[#0F2A43]">{schedule.shiftName}</strong>
                        <span className="mt-1 block truncate text-[11px] text-[#66727C]">
                          {isAdmin ? `${schedule.employeeName} · ` : ""}
                          {formatTime(schedule.scheduledStartUtc)}–{formatTime(schedule.scheduledEndUtc)}
                        </span>
                      </div>
                    </div>
                    <div className="text-[11px] leading-5 text-[#66727C]">
                      <span className="block">Vào: <b className="text-[#27445F]">{formatWorkDateTime(schedule.actualCheckInUtc)}</b></span>
                      <span className="block">Ra: <b className="text-[#27445F]">{formatWorkDateTime(schedule.actualCheckOutUtc)}</b></span>
                    </div>
                    <div className="flex flex-wrap items-center gap-2 md:justify-end">
                      <span className={`rounded-full border px-2.5 py-1 text-[10px] font-bold ${toneClass[workScheduleTone(schedule)]}`}>
                        {workScheduleDisplayStatus(schedule)}
                      </span>
                      {schedule.late && <span className="text-[10px] font-bold text-orange-700">Muộn {schedule.lateMinutes} phút</span>}
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
