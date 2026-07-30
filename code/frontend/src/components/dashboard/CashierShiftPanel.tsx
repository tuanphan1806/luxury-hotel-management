"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import {
  type CashierShift,
  type CashMovement,
  type PageResult,
  formatVnd,
  movementLabel,
} from "@/lib/cashier-shifts";
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from "@/lib/idempotency";

type ActionKind = "open" | "close" | null;

const emptyPage: PageResult<CashierShift> = {
  content: [],
  number: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

function unwrap<T>(response: { data?: { data?: T } }): T {
  return response.data?.data as T;
}

function dateTime(value?: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("vi-VN", {
    dateStyle: "short",
    timeStyle: "short",
    timeZone: "Asia/Ho_Chi_Minh",
  }).format(new Date(value));
}

function statusLabel(status: CashierShift["status"]) {
  return {
    OPEN: "Đang làm việc",
    CLOSING: "Đang kết thúc",
    CLOSED: "Đã kết thúc",
    CANCELLED: "Đã hủy",
  }[status];
}

function SummaryCard({
  label,
  value,
  tone = "navy",
}: {
  label: string;
  value: string;
  tone?: "navy" | "green" | "red" | "gold";
}) {
  const color = {
    navy: "text-[#0F2A43]",
    green: "text-emerald-700",
    red: "text-rose-700",
    gold: "text-[#8E6B2E]",
  }[tone];
  return (
    <div className="bg-white px-4 py-4 sm:px-5">
      <p className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#7A858E]">
        {label}
      </p>
      <p className={`mt-2 font-serif text-xl font-bold ${color}`}>{value}</p>
    </div>
  );
}

function ShiftSummary({ shift }: { shift: CashierShift }) {
  return (
    <div className="grid gap-px bg-[#0F2A43]/8 sm:grid-cols-2 xl:grid-cols-5">
      <SummaryCard label="Thu bằng tiền mặt" value={formatVnd(shift.cashIncomeAmount)} tone="green" />
      <SummaryCard label="Thu qua chuyển khoản" value={formatVnd(shift.transferIncomeAmount)} tone="green" />
      <SummaryCard label="Hoàn bằng tiền mặt" value={formatVnd(shift.cashRefundAmount)} tone="red" />
      <SummaryCard label="Hoàn qua chuyển khoản" value={formatVnd(shift.transferRefundAmount)} tone="red" />
      <SummaryCard label="Doanh thu thực nhận" value={formatVnd(shift.netAmount)} tone="gold" />
    </div>
  );
}

function MovementRow({ movement }: { movement: CashMovement }) {
  const incoming = movement.direction === "IN";
  return (
    <article className="grid gap-3 border-t border-[#0F2A43]/8 px-4 py-4 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_auto] sm:items-center sm:px-5">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={`rounded-full px-2.5 py-1 text-[10px] font-extrabold uppercase tracking-[0.12em] ${
              incoming
                ? "bg-emerald-50 text-emerald-700"
                : "bg-rose-50 text-rose-700"
            }`}
          >
            {incoming ? "Thu tiền" : "Hoàn tiền"}
          </span>
          <p className="font-bold text-[#0F2A43]">
            {movementLabel(movement.movementType)}
          </p>
        </div>
        {movement.reservationCode && (
          <Link
            href={`/dashboard/reservations?reservationCode=${encodeURIComponent(movement.reservationCode)}`}
            className="mt-1 inline-flex min-h-6 items-center text-xs font-bold text-[#8E6B2E] hover:text-[#0F2A43]"
          >
            Đơn {movement.reservationCode} →
          </Link>
        )}
      </div>
      <div className="text-xs leading-5 text-[#66727C]">
        <p>{dateTime(movement.occurredAtUtc)}</p>
        <p>{movement.createdByName}</p>
      </div>
      <p
        className={`text-right font-serif text-lg font-bold ${
          incoming ? "text-emerald-700" : "text-rose-700"
        }`}
      >
        {incoming ? "+" : "−"}
        {formatVnd(movement.amount)}
      </p>
    </article>
  );
}

export default function CashierShiftPanel({
  embedded = false,
  adminView = false,
}: {
  embedded?: boolean;
  adminView?: boolean;
}) {
  const [current, setCurrent] = useState<CashierShift | null>(null);
  const [history, setHistory] =
    useState<PageResult<CashierShift>>(emptyPage);
  const [historyPage, setHistoryPage] = useState(0);
  const [selected, setSelected] = useState<CashierShift | null>(null);
  const [action, setAction] = useState<ActionKind>(null);
  const [note, setNote] = useState("");
  const [loading, setLoading] = useState(true);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [lastUpdatedAt, setLastUpdatedAt] = useState<Date | null>(null);

  const loadCurrent = useCallback(async (silent = false) => {
    if (adminView) {
      setLoading(false);
      return;
    }
    if (!silent) setLoading(true);
    try {
      const response = await apiClient.get(
        "/api/accounting/cashier-shifts/current",
      );
      setCurrent(unwrap<CashierShift | null>(response) || null);
      setLastUpdatedAt(new Date());
    } catch (requestError) {
      setError(
        getApiErrorMessage(
          requestError,
          "Không thể tải ca thu ngân hiện tại",
        ),
      );
    } finally {
      if (!silent) setLoading(false);
    }
  }, [adminView]);

  const loadHistory = useCallback(async (silent = false) => {
    if (!silent) setHistoryLoading(true);
    try {
      const response = await apiClient.get(
        `/api/accounting/cashier-shifts?page=${historyPage}&size=20`,
      );
      setHistory(unwrap<PageResult<CashierShift>>(response) || emptyPage);
      setLastUpdatedAt(new Date());
    } catch (requestError) {
      setError(
        getApiErrorMessage(requestError, "Không thể tải lịch sử ca thu ngân"),
      );
    } finally {
      if (!silent) setHistoryLoading(false);
    }
  }, [historyPage]);

  const reload = useCallback(async () => {
    setError("");
    if (adminView) await loadHistory();
    else await Promise.all([loadCurrent(), loadHistory()]);
  }, [adminView, loadCurrent, loadHistory]);

  useEffect(() => {
    if (!adminView) void loadCurrent();
  }, [adminView, loadCurrent]);

  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);

  useEffect(() => {
    const refreshIntervalMs = adminView ? 60_000 : 30_000;
    const refreshVisible = () => {
      if (document.visibilityState !== "visible") return;
      if (adminView) void loadHistory(true);
      else void loadCurrent(true);
    };
    const timer = window.setInterval(refreshVisible, refreshIntervalMs);
    window.addEventListener("focus", refreshVisible);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", refreshVisible);
    };
  }, [adminView, loadCurrent, loadHistory]);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(""), 4200);
    return () => window.clearTimeout(timer);
  }, [notice]);

  const openAction = (kind: Exclude<ActionKind, null>) => {
    setError("");
    setNote("");
    setAction(kind);
  };

  const closeAction = () => {
    if (submitting) return;
    setAction(null);
    setError("");
  };

  const submit = async () => {
    if (!action) return;
    setSubmitting(true);
    setError("");
    const scope = `cashier:${action}:${current?.id || "new"}:${note.trim()}`;
    const headers = {
      "Idempotency-Key": getOrCreateIdempotencyKey(scope),
    };
    try {
      if (action === "open") {
        await apiClient.post(
          "/api/accounting/cashier-shifts",
          { note: note.trim() || undefined },
          { headers },
        );
        setNotice(
          "Đã bắt đầu ca. Tiền mặt được ghi theo người thao tác; chuyển khoản được hệ thống tổng hợp tự động.",
        );
      } else if (current) {
        await apiClient.post(
          `/api/accounting/cashier-shifts/${current.id}/close`,
          { note: note.trim() || undefined },
          { headers },
        );
        setNotice("Đã kết thúc ca. Số liệu trong ca đã được hệ thống tổng hợp.");
      }
      clearIdempotencyKey(scope);
      setAction(null);
      await reload();
    } catch (requestError) {
      setError(
        getApiErrorMessage(
          requestError,
          "Không thể hoàn tất thao tác ca thu ngân",
        ),
      );
    } finally {
      setSubmitting(false);
    }
  };

  const viewShift = async (shift: CashierShift) => {
    setError("");
    try {
      const response = await apiClient.get(
        `/api/accounting/cashier-shifts/${shift.id}`,
      );
      setSelected(unwrap<CashierShift>(response));
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể tải chi tiết ca"));
    }
  };

  const visibleCashMovements = (shift: CashierShift) =>
    shift.movements.filter(
      (movement) =>
        movement.movementType === "CASH_PAYMENT"
        || movement.movementType === "CASH_REFUND",
    );

  return (
    <section
      className={
        embedded
          ? "w-full"
          : "mx-auto w-full max-w-[1500px] px-4 py-6 sm:px-6 lg:px-8"
      }
    >
      <header
        className={`flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between ${
          embedded ? "pb-5" : "border-b border-[#0F2A43]/12 pb-6"
        }`}
      >
        <div>
          <p className="text-[11px] font-extrabold uppercase tracking-[0.2em] text-[#9A762F]">
            {adminView ? "Quản lý · ADMIN" : "Vận hành · STAFF"}
          </p>
          <h1 className="mt-2 font-serif text-3xl font-bold text-[#0F2A43] sm:text-4xl">
            {adminView ? "Quản lý ca thu ngân" : "Ca thu ngân"}
          </h1>
          <p className="mt-2 max-w-3xl text-sm leading-6 text-[#66727C]">
            {adminView
              ? "Xem thời gian và số tiền hệ thống đã ghi nhận trong từng ca của nhân viên."
              : "Chỉ cần bắt đầu và kết thúc ca. Các khoản thu, hoàn tiền được hệ thống tự ghi nhận từ đơn đặt phòng."}
          </p>
          {lastUpdatedAt && (
            <p className="mt-2 text-[11px] font-semibold text-[#7A858E]">
              Tự cập nhật mỗi {adminView ? "60" : "30"} giây · gần nhất{" "}
              {lastUpdatedAt.toLocaleTimeString("vi-VN", {
                hour: "2-digit",
                minute: "2-digit",
                second: "2-digit",
              })}
            </p>
          )}
        </div>
        {!adminView && !current && !loading && (
          <button
            type="button"
            onClick={() => openAction("open")}
            className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:-translate-y-0.5 hover:bg-[#173D5F] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2"
          >
            Bắt đầu ca
          </button>
        )}
      </header>

      {notice && (
        <div
          role="status"
          className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-semibold text-emerald-800"
        >
          {notice}
        </div>
      )}
      {error && !action && (
        <div
          role="alert"
          className="mt-5 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800"
        >
          {error}
        </div>
      )}

      {!adminView && (
        <>
          {loading ? (
            <div className="mt-6 grid gap-4 lg:grid-cols-3">
              {[1, 2, 3].map((item) => (
                <div
                  key={item}
                  className="h-32 animate-pulse rounded-2xl bg-[#0F2A43]/7"
                />
              ))}
            </div>
          ) : current ? (
            <section className="mt-6 overflow-hidden rounded-2xl border border-[#0F2A43]/12 bg-white shadow-[0_16px_45px_rgba(15,42,67,0.08)]">
              <div className="bg-[#0F2A43] px-5 py-5 text-white sm:px-6">
                <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                  <div>
                    <span className="rounded-full bg-emerald-400/16 px-2.5 py-1 text-[10px] font-extrabold uppercase tracking-[0.14em] text-emerald-200">
                      Đang làm việc
                    </span>
                    <p className="mt-3 font-serif text-2xl font-bold">
                      {current.openedByName}
                    </p>
                    <p className="mt-1 text-xs text-white/65">
                      Bắt đầu lúc {dateTime(current.openedAtUtc)} ·{" "}
                      {current.shiftCode}
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => openAction("close")}
                    className="min-h-11 rounded-lg bg-[#B8944F] px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#D1B575]"
                  >
                    Kết thúc ca
                  </button>
                </div>
              </div>
              <ShiftSummary shift={current} />
              <div className="border-t border-[#0F2A43]/8">
                <div className="px-5 py-4 sm:px-6">
                  <h2 className="font-serif text-xl font-bold text-[#0F2A43]">
                    Giao dịch tiền mặt trong ca
                  </h2>
                  <p className="mt-1 text-xs text-[#66727C]">
                    Chuyển khoản được tính trong phần tổng hợp phía trên; tiền mặt hiển thị theo đúng người thao tác.
                  </p>
                </div>
                {visibleCashMovements(current).length ? (
                  visibleCashMovements(current).map((movement) => (
                    <MovementRow key={movement.id} movement={movement} />
                  ))
                ) : (
                  <p className="border-t border-dashed border-[#0F2A43]/10 px-6 py-9 text-center text-sm text-[#66727C]">
                    Chưa có giao dịch tiền mặt trong ca.
                  </p>
                )}
              </div>
            </section>
          ) : (
            <section className="mt-6 rounded-2xl border border-dashed border-[#0F2A43]/20 bg-[#F8F6F0] px-6 py-12 text-center">
              <div
                className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-[#E8DFC9] text-2xl"
                aria-hidden="true"
              >
                ₫
              </div>
              <h2 className="mt-4 font-serif text-2xl font-bold text-[#0F2A43]">
                Chưa bắt đầu ca
              </h2>
              <p className="mx-auto mt-2 max-w-xl text-sm leading-6 text-[#66727C]">
                Bắt đầu ca trước khi STAFF thu hoặc hoàn tiền mặt. Không cần nhập số dư đầu ca.
              </p>
              <button
                type="button"
                onClick={() => openAction("open")}
                className="mt-5 min-h-11 rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#173D5F]"
              >
                Bắt đầu ca
              </button>
            </section>
          )}
        </>
      )}

      <section className={adminView ? "mt-1" : "mt-8"}>
        <div className="flex items-end justify-between gap-4">
          <div>
            <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#9A762F]">
              Lịch sử ca
            </p>
            <h2 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">
              Các ca gần đây
            </h2>
          </div>
          <p className="text-xs text-[#66727C]">
            {history.totalElements} ca
          </p>
        </div>
        <div className="mt-4 overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white">
          {history.content.length ? (
            history.content.map((shift) => (
              <button
                key={shift.id}
                type="button"
                onClick={() => void viewShift(shift)}
                className="grid min-h-16 w-full gap-3 border-b border-[#0F2A43]/8 px-4 py-4 text-left transition last:border-b-0 hover:bg-[#F8F6F0] sm:grid-cols-[1.2fr_1fr_1fr_auto] sm:items-center sm:px-5"
              >
                <div>
                  <p className="font-bold text-[#0F2A43]">
                    {shift.openedByName}
                  </p>
                  <p className="mt-1 text-xs text-[#66727C]">
                    {shift.shiftCode}
                  </p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#7A858E]">
                    Bắt đầu
                  </p>
                  <p className="mt-1 text-xs text-[#0F2A43]">
                    {dateTime(shift.openedAtUtc)}
                  </p>
                </div>
                <div>
                  <p className="text-[10px] font-bold uppercase tracking-[0.12em] text-[#7A858E]">
                    Kết thúc
                  </p>
                  <p className="mt-1 text-xs text-[#0F2A43]">
                    {shift.closedAtUtc
                      ? dateTime(shift.closedAtUtc)
                      : statusLabel(shift.status)}
                  </p>
                </div>
                <span className="text-sm font-bold text-[#8E6B2E]">
                  Xem chi tiết →
                </span>
              </button>
            ))
          ) : (
            <div className="px-6 py-10 text-center text-sm text-[#66727C]">
              {historyLoading ? "Đang tải lịch sử ca…" : "Chưa có lịch sử ca."}
            </div>
          )}
        </div>
        {history.totalPages > 1 && (
          <nav
            aria-label="Phân trang lịch sử ca"
            className="mt-4 flex items-center justify-end gap-3"
          >
            <button
              type="button"
              disabled={history.number <= 0 || historyLoading}
              onClick={() => setHistoryPage((page) => Math.max(0, page - 1))}
              className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold text-[#0F2A43] disabled:opacity-45"
            >
              Trang trước
            </button>
            <span className="text-xs font-semibold text-[#66727C]">
              {history.number + 1}/{history.totalPages}
            </span>
            <button
              type="button"
              disabled={history.number >= history.totalPages - 1 || historyLoading}
              onClick={() => setHistoryPage((page) => page + 1)}
              className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold text-[#0F2A43] disabled:opacity-45"
            >
              Trang sau
            </button>
          </nav>
        )}
      </section>

      <ViewportModal
        open={Boolean(action)}
        onClose={closeAction}
        labelledBy="cashier-action-title"
        busy={submitting}
        panelClassName="max-w-lg"
      >
        <div className="flex items-start justify-between border-b border-[#0F2A43]/10 px-5 py-4">
          <div>
            <p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#9A762F]">
              Ca thu ngân
            </p>
            <h2
              id="cashier-action-title"
              className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]"
            >
              {action === "close" ? "Kết thúc ca" : "Bắt đầu ca"}
            </h2>
          </div>
          <button
            type="button"
            onClick={closeAction}
            disabled={submitting}
            aria-label="Đóng"
            className="flex h-11 w-11 items-center justify-center rounded-full text-xl text-[#66727C] transition hover:bg-[#F1F0EA] disabled:opacity-40"
          >
            ×
          </button>
        </div>
        <div className="px-5 py-5">
          <div className="rounded-xl bg-[#F1F0EA] px-4 py-3 text-sm leading-6 text-[#52616D]">
            {action === "close"
              ? "Hệ thống sẽ chốt thời gian kết thúc và giữ nguyên toàn bộ khoản thu, hoàn tiền đã tự ghi nhận trong ca."
              : "Hệ thống sẽ ghi thời gian bắt đầu. Bạn không cần nhập tiền đầu ca hay tổng doanh thu."}
          </div>
          <label
            className="mt-4 block text-sm font-bold text-[#0F2A43]"
            htmlFor="cashier-note"
          >
            Ghi chú
          </label>
          <textarea
            id="cashier-note"
            data-modal-autofocus
            value={note}
            onChange={(event) => setNote(event.target.value)}
            maxLength={1000}
            rows={3}
            placeholder="Không bắt buộc"
            className="mt-2 w-full rounded-lg border border-[#0F2A43]/18 px-4 py-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
          />
          {error && (
            <p
              role="alert"
              className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-sm font-semibold text-rose-700"
            >
              {error}
            </p>
          )}
        </div>
        <div className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-5 py-4">
          <button
            type="button"
            onClick={closeAction}
            disabled={submitting}
            className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA] disabled:opacity-50"
          >
            Hủy
          </button>
          <button
            type="button"
            onClick={() => void submit()}
            disabled={submitting}
            className="flex min-h-11 min-w-36 items-center justify-center rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] disabled:opacity-50"
          >
            {submitting ? (
              <span
                className="h-5 w-5 animate-spin rounded-full border-2 border-white/30 border-t-white"
                aria-label="Đang xử lý"
              />
            ) : action === "close" ? (
              "Xác nhận kết thúc"
            ) : (
              "Xác nhận bắt đầu"
            )}
          </button>
        </div>
      </ViewportModal>

      <ViewportModal
        open={Boolean(selected)}
        onClose={() => setSelected(null)}
        labelledBy="shift-detail-title"
        panelClassName="max-w-4xl"
      >
        {selected && (
          <>
            <div className="flex items-start justify-between border-b border-[#0F2A43]/10 px-5 py-4">
              <div>
                <p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#9A762F]">
                  {statusLabel(selected.status)}
                </p>
                <h2
                  id="shift-detail-title"
                  className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]"
                >
                  {selected.openedByName}
                </h2>
                <p className="mt-1 text-xs text-[#66727C]">
                  {dateTime(selected.openedAtUtc)} →{" "}
                  {selected.closedAtUtc
                    ? dateTime(selected.closedAtUtc)
                    : "Đang làm việc"}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSelected(null)}
                aria-label="Đóng"
                className="flex h-11 w-11 items-center justify-center rounded-full text-xl text-[#66727C] hover:bg-[#F1F0EA]"
              >
                ×
              </button>
            </div>
            <div className="lux-scrollbar min-h-0 overflow-y-auto">
              <ShiftSummary shift={selected} />
              <div className="px-5 py-4">
                <h3 className="font-serif text-xl font-bold text-[#0F2A43]">
                  Giao dịch tiền mặt
                </h3>
                <p className="mt-1 text-xs text-[#66727C]">
                  Chuyển khoản được tổng hợp tự động theo thời gian của ca.
                </p>
              </div>
              {visibleCashMovements(selected).length ? (
                visibleCashMovements(selected).map((movement) => (
                  <MovementRow key={movement.id} movement={movement} />
                ))
              ) : (
                <p className="p-8 text-center text-sm text-[#66727C]">
                  Không có giao dịch tiền mặt trong ca.
                </p>
              )}
            </div>
          </>
        )}
      </ViewportModal>
    </section>
  );
}
