"use client";

import { useMemo } from "react";
import {
  formatWorkedMinutes,
  summarizeAttendance,
  summarizeAttendanceByDay,
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
  const summary = useMemo(() => summarizeAttendance(schedules, now), [now, schedules]);
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

      <section className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="attendance-ledger-title">
        <header className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-5 py-4 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-[10px] font-black uppercase tracking-[0.18em] text-[#9A7531]">Chấm công theo ngày</p>
            <h2 id="attendance-ledger-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Bảng chấm công trong kỳ</h2>
            <p className="mt-1 text-xs leading-5 text-[#66727C]">Giờ theo lịch, giờ thực tế và kết quả được đặt trên cùng một dòng để dễ đối chiếu.</p>
          </div>
          <div className="flex flex-wrap gap-2 text-[10px] font-bold text-[#526372]">
            <span className="rounded-full bg-[#F1F0EA] px-3 py-1.5">{days.length} ngày</span>
            <span className="rounded-full bg-[#F1F0EA] px-3 py-1.5">{summary.totalShifts} ca</span>
          </div>
        </header>

        <div className="hidden overflow-x-auto md:block">
          <table className="w-full min-w-[760px] border-collapse text-left">
            <thead className="bg-[#F7F5EF] text-[9px] font-black uppercase tracking-[0.12em] text-[#66727C]">
              <tr>
                <th className="px-5 py-3">Ca làm việc</th>
                {isAdmin && <th className="px-4 py-3">Nhân viên</th>}
                <th className="px-4 py-3">Giờ vào</th>
                <th className="px-4 py-3">Giờ ra</th>
                <th className="px-5 py-3">Kết quả</th>
              </tr>
            </thead>
            {days.map((day) => (
              <tbody key={day.workDate} className="border-t border-[#0F2A43]/10">
                <tr className="bg-[#EEE7D9]/75">
                  <th colSpan={isAdmin ? 5 : 4} className="px-5 py-2.5">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <span className="capitalize text-xs font-black text-[#0F2A43]">{formatDate(day.workDate)}</span>
                      <span className="text-[10px] font-semibold text-[#66727C]">
                        {day.totalShifts} ca · {formatWorkedMinutes(day.workedMinutes)} · <b className="text-emerald-700">{day.onTimeShifts} đúng giờ</b> · <b className="text-orange-700">{day.lateShifts} muộn</b> · <b className="text-rose-700">{day.absentShifts} vắng</b>{day.unrecordedShifts > 0 ? <> · <b className="text-slate-700">{day.unrecordedShifts} chưa ghi nhận</b></> : null}
                      </span>
                    </div>
                  </th>
                </tr>
                {day.schedules.map((schedule) => (
                  <tr key={schedule.id} className="border-t border-[#0F2A43]/8 transition duration-200 hover:bg-[#FBFAF6]">
                    <td className="px-5 py-3.5">
                      <div className="flex min-w-0 items-start gap-3">
                        <span className="mt-0.5 h-10 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} aria-hidden="true" />
                        <div className="min-w-0">
                          <strong className="block truncate text-sm text-[#0F2A43]">{schedule.shiftName}</strong>
                          <span className="mt-1 block text-[11px] tabular-nums text-[#66727C]">{formatTime(schedule.scheduledStartUtc)}–{formatTime(schedule.scheduledEndUtc)}</span>
                        </div>
                      </div>
                    </td>
                    {isAdmin && <td className="px-4 py-3.5 text-xs font-bold text-[#27445F]">{schedule.employeeName}</td>}
                    <td className="px-4 py-3.5 text-xs font-semibold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckInUtc)}</td>
                    <td className="px-4 py-3.5 text-xs font-semibold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckOutUtc)}</td>
                    <td className="px-5 py-3.5"><AttendanceResult schedule={schedule} /></td>
                  </tr>
                ))}
              </tbody>
            ))}
          </table>
        </div>

        <div className="space-y-3 bg-[#F4F1E9]/55 p-3 md:hidden">
          {days.map((day) => (
            <section key={day.workDate} className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white">
              <header className="bg-[#EEE7D9]/75 px-3 py-2.5">
                <strong className="block capitalize text-xs text-[#0F2A43]">{formatDate(day.workDate)}</strong>
                <span className="mt-1 block text-[9px] font-semibold text-[#66727C]">{day.totalShifts} ca · {formatWorkedMinutes(day.workedMinutes)} · {day.lateShifts} muộn · {day.absentShifts} vắng</span>
              </header>
              <div className="divide-y divide-[#0F2A43]/8">
                {day.schedules.map((schedule) => (
                  <article key={schedule.id} className="p-3">
                    <div className="flex items-start gap-2.5">
                      <span className="mt-0.5 h-10 w-1.5 shrink-0 rounded-full" style={{ backgroundColor: schedule.shiftColor }} aria-hidden="true" />
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-start justify-between gap-2">
                          <div className="min-w-0">
                            <strong className="block truncate text-sm text-[#0F2A43]">{schedule.shiftName}</strong>
                            <span className="mt-0.5 block text-[10px] tabular-nums text-[#66727C]">Lịch {formatTime(schedule.scheduledStartUtc)}–{formatTime(schedule.scheduledEndUtc)}</span>
                            {isAdmin && <span className="mt-1 block truncate text-[11px] font-bold text-[#27445F]">{schedule.employeeName}</span>}
                          </div>
                          <AttendanceResult schedule={schedule} />
                        </div>
                        <dl className="mt-3 grid grid-cols-2 gap-2 rounded-lg bg-[#F7F5EF] p-2.5 text-[10px]">
                          <div><dt className="font-bold uppercase tracking-wide text-[#7A858D]">Giờ vào</dt><dd className="mt-1 font-bold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckInUtc)}</dd></div>
                          <div><dt className="font-bold uppercase tracking-wide text-[#7A858D]">Giờ ra</dt><dd className="mt-1 font-bold tabular-nums text-[#27445F]">{formatWorkDateTime(schedule.actualCheckOutUtc)}</dd></div>
                        </dl>
                      </div>
                    </div>
                  </article>
                ))}
              </div>
            </section>
          ))}
        </div>
      </section>
    </div>
  );
}
