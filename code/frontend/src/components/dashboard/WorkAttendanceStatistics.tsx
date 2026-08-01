"use client";

import { useMemo, useState } from "react";
import {
  formatWorkedMinutes,
  summarizeAttendance,
  summarizeAttendanceByEmployee,
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
  weekday: "short",
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

function AttendanceResult({ schedule }: { schedule: WorkSchedule }) {
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <span className={`rounded-full border px-2.5 py-1 text-[10px] font-bold ${toneClass[workScheduleTone(schedule)]}`}>
        {workScheduleDisplayStatus(schedule)}
      </span>
      {schedule.late && (
        <span className="rounded-full bg-orange-50 px-2 py-1 text-[10px] font-bold text-orange-700">
          Muộn {schedule.lateMinutes} phút
        </span>
      )}
      {schedule.autoCheckOut && (
        <span className="rounded-full bg-slate-100 px-2 py-1 text-[10px] font-bold text-slate-600">
          Tự động kết thúc
        </span>
      )}
    </div>
  );
}

export default function WorkAttendanceStatistics({ schedules, isAdmin, periodLabel, now }: WorkAttendanceStatisticsProps) {
  const [expandedEmployeeIds, setExpandedEmployeeIds] = useState<Set<number>>(() => new Set());
  const summary = useMemo(() => summarizeAttendance(schedules, now), [now, schedules]);
  const employeeGroups = useMemo(() => {
    const schedulesByEmployee = new Map<number, WorkSchedule[]>();
    schedules.forEach((schedule) => {
      const group = schedulesByEmployee.get(schedule.employeeId) || [];
      group.push(schedule);
      schedulesByEmployee.set(schedule.employeeId, group);
    });

    return summarizeAttendanceByEmployee(schedules, now).map((employee) => ({
      ...employee,
      schedules: (schedulesByEmployee.get(employee.employeeId) || []).toSorted((left, right) =>
        right.scheduledStartUtc.localeCompare(left.scheduledStartUtc)),
    }));
  }, [now, schedules]);
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
              <span className="text-white/60">Nhân viên có lịch</span><strong className="text-right tabular-nums">{employeeGroups.length}</strong>
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
            <p className={`mt-1 leading-5 ${attentionCount > 0 ? "text-orange-800/75" : "text-emerald-800/75"}`}>{attentionCount > 0 ? "Mở chi tiết nhân viên để xem ca vắng hoặc quá hạn chưa chấm công." : "Dữ liệu chấm công đã được ghi nhận đầy đủ cho các ca đến hạn."}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <span className="rounded-full bg-white/75 px-3 py-1.5 font-bold text-[#0F2A43]">{summary.upcomingShifts} ca sắp tới</span>
            <span className="rounded-full bg-white/75 px-3 py-1.5 font-bold text-amber-800">{summary.awaitingCheckInShifts} chờ check-in</span>
          </div>
        </div>
      </section>

      <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="attendance-employees-title">
        <header className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-5 py-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#9A7531]">Theo từng nhân viên</p>
            <h2 id="attendance-employees-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Kết quả chấm công trong kỳ</h2>
            <p className="mt-1 text-xs leading-5 text-[#66727C]">{isAdmin ? "Mỗi nhân viên là một nhóm riêng. Mở chi tiết để xem toàn bộ ca và giờ chấm công trong kỳ lọc." : "Mở chi tiết để xem toàn bộ ca, giờ vào/ra và kết quả chấm công của bạn."}</p>
          </div>
          <div className="flex flex-wrap gap-2 text-[10px] font-bold text-[#526372]">
            <span className="rounded-full bg-[#F1F0EA] px-3 py-1.5">{employeeGroups.length} nhân viên</span>
            <span className="rounded-full bg-[#F1F0EA] px-3 py-1.5">{summary.totalShifts} ca tính chấm công</span>
          </div>
        </header>

        <div className="space-y-3 bg-[#F4F1E9]/55 p-3 sm:p-4">
          {employeeGroups.map((employee) => {
            const attention = employee.absentShifts + employee.unrecordedShifts;
            const expanded = expandedEmployeeIds.has(employee.employeeId);
            return (
              <details
                key={employee.employeeId}
                open={expanded}
                onToggle={(event) => {
                  const isOpen = event.currentTarget.open;
                  setExpandedEmployeeIds((current) => {
                    const next = new Set(current);
                    if (isOpen) next.add(employee.employeeId);
                    else next.delete(employee.employeeId);
                    return next;
                  });
                }}
                className="group overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white"
              >
                <summary className="grid min-h-20 cursor-pointer list-none items-center gap-3 px-4 py-3 transition duration-200 hover:bg-[#FBFAF6] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] lg:grid-cols-[minmax(15rem,1.3fr)_repeat(5,minmax(5rem,0.55fr))_auto]">
                  <div className="flex min-w-0 items-center gap-3">
                    <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-full bg-[#0F2A43] text-sm font-black text-white" aria-hidden="true">
                      {employee.employeeName.trim().slice(0, 1).toUpperCase()}
                    </span>
                    <div className="min-w-0">
                      <strong className="block truncate text-sm text-[#0F2A43]">{employee.employeeName}</strong>
                      <span className="mt-1 block text-[10px] font-semibold text-[#66727C]">{employee.schedules.length} ca trong dữ liệu · {employee.attendanceRate}% đi làm</span>
                    </div>
                  </div>
                  <span className="hidden text-center lg:block"><b className="block text-base tabular-nums text-[#0F2A43]">{employee.totalShifts}</b><small className="text-[9px] font-bold uppercase tracking-wide text-[#7A858D]">Tổng ca</small></span>
                  <span className="hidden text-center lg:block"><b className="block text-base tabular-nums text-emerald-700">{employee.onTimeShifts}</b><small className="text-[9px] font-bold uppercase tracking-wide text-[#7A858D]">Đúng giờ</small></span>
                  <span className="hidden text-center lg:block"><b className="block text-base tabular-nums text-orange-700">{employee.lateShifts}</b><small className="text-[9px] font-bold uppercase tracking-wide text-[#7A858D]">Đi muộn</small></span>
                  <span className="hidden text-center lg:block"><b className="block text-base tabular-nums text-rose-700">{attention}</b><small className="text-[9px] font-bold uppercase tracking-wide text-[#7A858D]">Cần xem</small></span>
                  <span className="hidden text-center lg:block"><b className="block text-sm tabular-nums text-[#27445F]">{formatWorkedMinutes(employee.workedMinutes)}</b><small className="text-[9px] font-bold uppercase tracking-wide text-[#7A858D]">Thực tế</small></span>
                  <div className="flex items-center justify-end gap-2">
                    <span className={`rounded-full px-2.5 py-1 text-[9px] font-black ${attention > 0 ? "bg-rose-50 text-rose-700" : "bg-emerald-50 text-emerald-700"}`}>{attention > 0 ? `${attention} cần xem` : "Ổn định"}</span>
                    <span className="flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/15 text-[#0F2A43] transition duration-200 group-open:rotate-180" aria-hidden="true">
                      <svg viewBox="0 0 24 24" className="h-4 w-4 fill-none stroke-current" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m6 9 6 6 6-6" /></svg>
                    </span>
                  </div>
                  <div className="col-span-full grid grid-cols-4 gap-2 lg:hidden">
                    <span className="rounded-lg bg-[#F1F0EA] p-2 text-center text-[9px] font-bold text-[#66727C]"><b className="block text-sm text-[#0F2A43]">{employee.totalShifts}</b>Tổng ca</span>
                    <span className="rounded-lg bg-emerald-50 p-2 text-center text-[9px] font-bold text-emerald-700"><b className="block text-sm">{employee.onTimeShifts}</b>Đúng giờ</span>
                    <span className="rounded-lg bg-orange-50 p-2 text-center text-[9px] font-bold text-orange-700"><b className="block text-sm">{employee.lateShifts}</b>Muộn</span>
                    <span className="rounded-lg bg-rose-50 p-2 text-center text-[9px] font-bold text-rose-700"><b className="block text-sm">{employee.absentShifts}</b>Vắng</span>
                  </div>
                </summary>

                {expanded && <div className="border-t border-[#0F2A43]/10">
                  <div className="hidden overflow-x-auto md:block">
                    <table className="w-full min-w-[760px] border-collapse text-left">
                      <thead className="bg-[#F7F5EF] text-[9px] font-black uppercase tracking-[0.12em] text-[#66727C]">
                        <tr><th className="px-5 py-3">Ngày & ca</th><th className="px-4 py-3">Giờ theo lịch</th><th className="px-4 py-3">Giờ vào</th><th className="px-4 py-3">Giờ ra</th><th className="px-5 py-3">Kết quả</th></tr>
                      </thead>
                      <tbody className="divide-y divide-[#0F2A43]/8">
                        {employee.schedules.map((schedule) => (
                          <tr key={schedule.id} className="transition duration-200 hover:bg-[#FBFAF6]">
                            <td className="px-5 py-3.5"><div className="flex min-w-0 items-start gap-3"><span className="mt-0.5 h-10 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} aria-hidden="true" /><div><strong className="block text-sm text-[#0F2A43]">{schedule.shiftName}</strong><span className="mt-1 block text-[10px] capitalize text-[#66727C]">{formatDate(schedule.workDate)}</span></div></div></td>
                            <td className="px-4 py-3.5 text-xs font-semibold tabular-nums text-[#27445F]">{formatTime(schedule.scheduledStartUtc)}–{formatTime(schedule.scheduledEndUtc)}</td>
                            <td className="px-4 py-3.5 text-xs font-semibold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckInUtc)}</td>
                            <td className="px-4 py-3.5 text-xs font-semibold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckOutUtc)}</td>
                            <td className="px-5 py-3.5"><AttendanceResult schedule={schedule} /></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="divide-y divide-[#0F2A43]/8 md:hidden">
                    {employee.schedules.map((schedule) => (
                      <article key={schedule.id} className="p-3">
                        <div className="flex items-start gap-2.5"><span className="mt-0.5 h-10 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} aria-hidden="true" /><div className="min-w-0 flex-1"><div className="flex flex-wrap items-start justify-between gap-2"><div><strong className="block text-sm text-[#0F2A43]">{schedule.shiftName}</strong><span className="mt-0.5 block text-[10px] capitalize text-[#66727C]">{formatDate(schedule.workDate)} · {formatTime(schedule.scheduledStartUtc)}–{formatTime(schedule.scheduledEndUtc)}</span></div><AttendanceResult schedule={schedule} /></div><dl className="mt-3 grid grid-cols-2 gap-2 rounded-lg bg-[#F7F5EF] p-2.5 text-[10px]"><div><dt className="font-bold uppercase tracking-wide text-[#7A858D]">Giờ vào</dt><dd className="mt-1 font-bold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckInUtc)}</dd></div><div><dt className="font-bold uppercase tracking-wide text-[#7A858D]">Giờ ra</dt><dd className="mt-1 font-bold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckOutUtc)}</dd></div></dl></div></div>
                      </article>
                    ))}
                  </div>
                </div>}
              </details>
            );
          })}
        </div>
      </section>
    </div>
  );
}
