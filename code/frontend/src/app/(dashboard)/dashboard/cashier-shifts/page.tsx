"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import {
  type CashierShift,
  type CashMovement,
  type PageResult,
  formatVnd,
  movementLabel,
  parseWholeVnd,
} from "@/lib/cashier-shifts";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";

type ModalKind = "open" | "cash-in" | "cash-out" | "close" | null;

const emptyPage: PageResult<CashierShift> = {
  content: [], number: 0, size: 20, totalElements: 0, totalPages: 0,
};

function unwrap<T>(response: { data?: { data?: T } }): T {
  return response.data?.data as T;
}

function dateTime(value?: string | null) {
  if (!value) return "—";
  return new Intl.DateTimeFormat("vi-VN", {
    dateStyle: "short", timeStyle: "short", timeZone: "Asia/Ho_Chi_Minh",
  }).format(new Date(value));
}

function statusLabel(status: CashierShift["status"]) {
  return { OPEN: "Đang mở", CLOSING: "Đang đóng", CLOSED: "Đã đóng", CANCELLED: "Đã hủy" }[status];
}

function MovementRow({ movement }: { movement: CashMovement }) {
  const incoming = movement.direction === "IN";
  return (
    <div className="grid gap-3 border-b border-[#0F2A43]/8 px-4 py-4 last:border-b-0 sm:grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)_auto] sm:items-center">
      <div className="min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <span className={`rounded-full px-2.5 py-1 text-[10px] font-extrabold uppercase tracking-[0.12em] ${incoming ? "bg-emerald-50 text-emerald-700" : "bg-rose-50 text-rose-700"}`}>
            {incoming ? "Thu vào" : "Chi ra"}
          </span>
          <p className="font-bold text-[#0F2A43]">{movementLabel(movement.movementType)}</p>
        </div>
        <p className="mt-1 truncate text-xs text-[#66727C]">{movement.reason || "Không có ghi chú"}</p>
        {movement.reservationCode && (
          <Link href={`/dashboard/reservations?reservationCode=${encodeURIComponent(movement.reservationCode)}`} className="mt-1 inline-flex min-h-6 items-center text-xs font-bold text-[#8E6B2E] hover:text-[#0F2A43]">
            {movement.reservationCode} →
          </Link>
        )}
      </div>
      <div className="text-xs leading-5 text-[#66727C]"><p>{dateTime(movement.occurredAtUtc)}</p><p>{movement.createdByName}</p></div>
      <p className={`text-right font-serif text-lg font-bold ${incoming ? "text-emerald-700" : "text-rose-700"}`}>
        {incoming ? "+" : "−"}{formatVnd(movement.amount)}
      </p>
    </div>
  );
}

export default function CashierShiftsPage() {
  const [current, setCurrent] = useState<CashierShift | null>(null);
  const [history, setHistory] = useState<PageResult<CashierShift>>(emptyPage);
  const [historyPage, setHistoryPage] = useState(0);
  const [selected, setSelected] = useState<CashierShift | null>(null);
  const [loading, setLoading] = useState(true);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [modal, setModal] = useState<ModalKind>(null);
  const [amount, setAmount] = useState("");
  const [note, setNote] = useState("");
  const [varianceReason, setVarianceReason] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadCurrent = useCallback(async () => {
    setLoading(true);
    try {
      const currentResponse = await apiClient.get("/api/accounting/cashier-shifts/current");
      setCurrent(unwrap<CashierShift | null>(currentResponse) || null);
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể tải ca thu ngân hiện tại"));
    } finally {
      setLoading(false);
    }
  }, []);

  const loadHistory = useCallback(async () => {
    setHistoryLoading(true);
    try {
      const historyResponse = await apiClient.get(`/api/accounting/cashier-shifts?page=${historyPage}&size=20`);
      setHistory(unwrap<PageResult<CashierShift>>(historyResponse) || emptyPage);
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể tải lịch sử ca thu ngân"));
    } finally {
      setHistoryLoading(false);
    }
  }, [historyPage]);

  const loadAll = useCallback(async () => {
    setError("");
    await Promise.all([loadCurrent(), loadHistory()]);
  }, [loadCurrent, loadHistory]);

  useEffect(() => { void loadCurrent(); }, [loadCurrent]);
  useEffect(() => { void loadHistory(); }, [loadHistory]);
  useEffect(() => {
    if (!notice) return;
    const timeout = window.setTimeout(() => setNotice(""), 4200);
    return () => window.clearTimeout(timeout);
  }, [notice]);

  const expected = Number(current?.expectedCashAmount || 0);
  const enteredAmount = parseWholeVnd(amount);
  const closeVariance = modal === "close" && enteredAmount != null ? enteredAmount - expected : 0;
  const formError = useMemo(() => {
    if (!modal) return "";
    if (enteredAmount == null) return "Số tiền phải là số nguyên VND hợp lệ.";
    if ((modal === "cash-in" || modal === "cash-out") && enteredAmount <= 0) return "Số tiền thu/chi phải lớn hơn 0.";
    if (modal !== "open" && modal !== "close" && note.trim().length < 5) return "Lý do thu/chi cần ít nhất 5 ký tự.";
    if (modal === "close" && closeVariance !== 0 && varianceReason.trim().length < 5) return "Ca đang chênh lệch; cần nhập lý do ít nhất 5 ký tự.";
    return "";
  }, [closeVariance, enteredAmount, modal, note, varianceReason]);

  const openModal = async (kind: Exclude<ModalKind, null>) => {
    setError(""); setNote(""); setVarianceReason("");
    if (kind === "close" && current) {
      try {
        const response = await apiClient.get(`/api/accounting/cashier-shifts/${current.id}/preview-close`);
        const refreshed = unwrap<CashierShift>(response);
        setCurrent(refreshed);
        setAmount(String(refreshed.expectedCashAmount || 0));
      } catch (requestError) {
        setError(getApiErrorMessage(requestError, "Không thể đối chiếu số tiền cuối ca"));
        return;
      }
    } else setAmount(kind === "open" ? "0" : "");
    setModal(kind);
  };

  const closeModal = () => { if (!submitting) { setModal(null); setError(""); } };

  const submit = async () => {
    if (!modal || formError || enteredAmount == null) return;
    setSubmitting(true); setError("");
    const scope = `cashier:${modal}:${current?.id || "new"}:${enteredAmount}:${note.trim()}:${varianceReason.trim()}`;
    const headers = { "Idempotency-Key": getOrCreateIdempotencyKey(scope) };
    try {
      if (modal === "open") {
        await apiClient.post("/api/accounting/cashier-shifts", { openingCashAmount: enteredAmount, note: note.trim() || undefined }, { headers });
        setNotice("Đã mở ca. Các thao tác tiền mặt từ bây giờ sẽ được ghi vào ca này.");
      } else if (modal === "cash-in" || modal === "cash-out") {
        await apiClient.post(`/api/accounting/cashier-shifts/${current?.id}/${modal}`, { amount: enteredAmount, reason: note.trim() }, { headers });
        setNotice(modal === "cash-in" ? "Đã ghi nhận tiền thu vào két." : "Đã ghi nhận tiền chi khỏi két.");
      } else {
        await apiClient.post(`/api/accounting/cashier-shifts/${current?.id}/close`, {
          countedCashAmount: enteredAmount, note: note.trim() || undefined, varianceReason: varianceReason.trim() || undefined,
        }, { headers });
        setNotice("Đã đóng ca và khóa sổ tiền mặt của ca này.");
      }
      clearIdempotencyKey(scope);
      setModal(null);
      await loadAll();
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể hoàn tất thao tác ca thu ngân"));
    } finally { setSubmitting(false); }
  };

  const viewShift = async (shift: CashierShift) => {
    setError("");
    try {
      const response = await apiClient.get(`/api/accounting/cashier-shifts/${shift.id}`);
      setSelected(unwrap<CashierShift>(response));
    } catch (requestError) { setError(getApiErrorMessage(requestError, "Không thể tải chi tiết ca")); }
  };

  return (
    <main className="mx-auto w-full max-w-[1500px] px-4 py-6 sm:px-6 lg:px-8">
      <header className="flex flex-col gap-4 border-b border-[#0F2A43]/12 pb-6 lg:flex-row lg:items-end lg:justify-between">
        <div><p className="text-[11px] font-extrabold uppercase tracking-[0.2em] text-[#9A762F]">Kế toán vận hành</p><h1 className="mt-2 font-serif text-3xl font-bold text-[#0F2A43] sm:text-4xl">Ca thu ngân & sổ tiền mặt</h1><p className="mt-2 max-w-3xl text-sm leading-6 text-[#66727C]">Mỗi khoản thu, hoàn hoặc chi tiền mặt được gắn với đúng nhân viên và ca làm việc. Sổ đã đóng không thể sửa hoặc xóa.</p></div>
        {!current && !loading && <button type="button" onClick={() => void openModal("open")} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:-translate-y-0.5 hover:bg-[#173D5F] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] focus-visible:ring-offset-2">Mở ca thu ngân</button>}
      </header>

      {notice && <div role="status" className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-semibold text-emerald-800">{notice}</div>}
      {error && !modal && <div role="alert" className="mt-5 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800">{error}</div>}

      {loading ? <div className="mt-6 grid gap-4 lg:grid-cols-3" aria-label="Đang tải ca thu ngân">{[1, 2, 3].map((item) => <div key={item} className="h-36 animate-pulse rounded-2xl bg-[#0F2A43]/7" />)}</div> : current ? (
        <section className="mt-6 overflow-hidden rounded-2xl border border-[#0F2A43]/12 bg-white shadow-[0_16px_45px_rgba(15,42,67,0.08)]">
          <div className="bg-[#0F2A43] px-5 py-5 text-white sm:px-6"><div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between"><div><div className="flex flex-wrap items-center gap-2"><span className="rounded-full bg-emerald-400/16 px-2.5 py-1 text-[10px] font-extrabold uppercase tracking-[0.14em] text-emerald-200">Đang mở</span><span className="text-xs text-white/65">{current.shiftCode}</span></div><p className="mt-2 font-serif text-2xl font-bold">{current.openedByName}</p><p className="mt-1 text-xs text-white/65">Mở lúc {dateTime(current.openedAtUtc)}</p></div><div className="flex flex-wrap gap-2"><button type="button" onClick={() => void openModal("cash-in")} className="min-h-11 rounded-lg border border-white/20 px-4 text-sm font-bold transition hover:bg-white/10">+ Thu khác</button><button type="button" onClick={() => void openModal("cash-out")} className="min-h-11 rounded-lg border border-white/20 px-4 text-sm font-bold transition hover:bg-white/10">− Chi khác</button><button type="button" onClick={() => void openModal("close")} className="min-h-11 rounded-lg bg-[#B8944F] px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#D1B575]">Kiểm đếm & đóng ca</button></div></div></div>
          <div className="grid gap-px bg-[#0F2A43]/8 sm:grid-cols-3">{[["Tiền đầu ca", formatVnd(current.openingCashAmount)], ["Tiền hệ thống phải có", formatVnd(current.expectedCashAmount)], ["Số bút toán", `${current.movementCount} giao dịch`]].map(([label, value]) => <div key={label} className="bg-white px-5 py-5 sm:px-6"><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#7A858E]">{label}</p><p className="mt-2 font-serif text-2xl font-bold text-[#0F2A43]">{value}</p></div>)}</div>
          <div className="border-t border-[#0F2A43]/8"><div className="flex items-center justify-between px-5 py-4 sm:px-6"><h2 className="font-serif text-xl font-bold text-[#0F2A43]">Phát sinh trong ca</h2><span className="text-xs text-[#66727C]">Cũ nhất → mới nhất</span></div>{current.movements.length ? current.movements.map((movement) => <MovementRow key={movement.id} movement={movement} />) : <div className="border-t border-dashed border-[#0F2A43]/10 px-6 py-10 text-center text-sm text-[#66727C]">Chưa có phát sinh tiền mặt trong ca.</div>}</div>
        </section>
      ) : <section className="mt-6 rounded-2xl border border-dashed border-[#0F2A43]/20 bg-[#F8F6F0] px-6 py-12 text-center"><div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-[#E8DFC9] text-2xl" aria-hidden="true">₫</div><h2 className="mt-4 font-serif text-2xl font-bold text-[#0F2A43]">Chưa có ca thu ngân đang mở</h2><p className="mx-auto mt-2 max-w-xl text-sm leading-6 text-[#66727C]">Mở ca và nhập số tiền thực tế trong két trước khi thu hoặc hoàn tiền mặt.</p><button type="button" onClick={() => void openModal("open")} className="mt-5 min-h-11 rounded-lg bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#173D5F]">Mở ca ngay</button></section>}

      <section className="mt-8"><div className="flex items-end justify-between gap-4"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#9A762F]">Lịch sử bất biến</p><h2 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Các ca gần đây</h2></div><p className="text-xs text-[#66727C]">{history.totalElements} ca</p></div><div className="mt-4 overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white">{history.content.length ? history.content.map((shift) => <button key={shift.id} type="button" onClick={() => void viewShift(shift)} className="grid min-h-16 w-full gap-3 border-b border-[#0F2A43]/8 px-4 py-4 text-left transition last:border-b-0 hover:bg-[#F8F6F0] sm:grid-cols-[1.2fr_1fr_1fr_auto] sm:items-center sm:px-5"><div><p className="font-bold text-[#0F2A43]">{shift.openedByName}</p><p className="mt-1 text-xs text-[#66727C]">{shift.shiftCode}</p></div><div><p className="text-xs text-[#66727C]">{dateTime(shift.openedAtUtc)}</p><p className="mt-1 text-xs font-bold text-[#0F2A43]">{statusLabel(shift.status)}</p></div><div><p className="text-xs text-[#66727C]">Tiền hệ thống</p><p className="mt-1 font-bold text-[#0F2A43]">{formatVnd(shift.expectedCashAmount)}</p></div><span className="text-sm font-bold text-[#8E6B2E]">Xem chi tiết →</span></button>) : <div className="px-6 py-10 text-center text-sm text-[#66727C]">{historyLoading ? "Đang tải lịch sử ca…" : "Chưa có lịch sử ca."}</div>}</div>{history.totalPages > 1 && <nav aria-label="Phân trang lịch sử ca" className="mt-4 flex items-center justify-end gap-3"><button type="button" disabled={history.number <= 0 || historyLoading} onClick={() => setHistoryPage((page) => Math.max(0, page - 1))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA] disabled:cursor-not-allowed disabled:opacity-45">← Trang trước</button><span className="text-xs font-semibold text-[#66727C]">Trang {history.number + 1}/{history.totalPages}</span><button type="button" disabled={history.number >= history.totalPages - 1 || historyLoading} onClick={() => setHistoryPage((page) => page + 1)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA] disabled:cursor-not-allowed disabled:opacity-45">Trang sau →</button></nav>}</section>

      <ViewportModal open={Boolean(modal)} onClose={closeModal} labelledBy="cashier-action-title" busy={submitting} panelClassName="max-w-lg">
        <div className="flex items-start justify-between border-b border-[#0F2A43]/10 px-5 py-4"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#9A762F]">Sổ tiền mặt</p><h2 id="cashier-action-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{{ open: "Mở ca thu ngân", "cash-in": "Ghi nhận tiền thu", "cash-out": "Ghi nhận tiền chi", close: "Kiểm đếm cuối ca" }[modal || "open"]}</h2></div><button type="button" onClick={closeModal} disabled={submitting} aria-label="Đóng" className="flex h-11 w-11 items-center justify-center rounded-full text-xl text-[#66727C] transition hover:bg-[#F1F0EA] disabled:opacity-40">×</button></div>
        <div className="lux-scrollbar min-h-0 overflow-y-auto px-5 py-5">
          {modal === "close" && <div className="mb-4 grid grid-cols-2 gap-3 rounded-xl bg-[#F1F0EA] p-4 text-sm"><div><p className="text-xs text-[#66727C]">Hệ thống phải có</p><p className="mt-1 font-bold text-[#0F2A43]">{formatVnd(expected)}</p></div><div><p className="text-xs text-[#66727C]">Chênh lệch đang nhập</p><p className={`mt-1 font-bold ${closeVariance === 0 ? "text-emerald-700" : "text-rose-700"}`}>{closeVariance > 0 ? "+" : ""}{formatVnd(closeVariance)}</p></div></div>}
          <label className="block text-sm font-bold text-[#0F2A43]" htmlFor="cashier-amount">{modal === "open" ? "Tiền thực tế đầu ca" : modal === "close" ? "Tiền thực tế kiểm đếm" : "Số tiền"} *</label>
          <div className="mt-2 flex items-center rounded-lg border border-[#0F2A43]/18 bg-white focus-within:border-[#B8944F] focus-within:ring-2 focus-within:ring-[#B8944F]/20"><input id="cashier-amount" data-modal-autofocus inputMode="numeric" value={amount} onChange={(event) => setAmount(event.target.value.replace(/[^\d.\s]/g, ""))} className="min-h-12 min-w-0 flex-1 bg-transparent px-4 font-bold text-[#0F2A43] outline-none" placeholder="0" /><span className="px-4 text-sm font-bold text-[#66727C]">VND</span></div>
          {(modal === "cash-in" || modal === "cash-out") && <><label className="mt-4 block text-sm font-bold text-[#0F2A43]" htmlFor="cashier-reason">Lý do *</label><textarea id="cashier-reason" value={note} onChange={(event) => setNote(event.target.value)} maxLength={500} rows={3} className="mt-2 w-full rounded-lg border border-[#0F2A43]/18 px-4 py-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" placeholder="Mô tả rõ khoản thu/chi..." /></>}
          {(modal === "open" || modal === "close") && <><label className="mt-4 block text-sm font-bold text-[#0F2A43]" htmlFor="cashier-note">Ghi chú</label><textarea id="cashier-note" value={note} onChange={(event) => setNote(event.target.value)} maxLength={1000} rows={2} className="mt-2 w-full rounded-lg border border-[#0F2A43]/18 px-4 py-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" /></>}
          {modal === "close" && closeVariance !== 0 && <><label className="mt-4 block text-sm font-bold text-[#0F2A43]" htmlFor="variance-reason">Lý do chênh lệch *</label><textarea id="variance-reason" value={varianceReason} onChange={(event) => setVarianceReason(event.target.value)} maxLength={500} rows={3} className="mt-2 w-full rounded-lg border border-rose-300 px-4 py-3 text-sm outline-none focus:ring-2 focus:ring-rose-200" placeholder="Giải thích nguyên nhân thiếu/thừa tiền..." /></>}
          {formError && <p className="mt-3 text-sm font-semibold text-rose-700">{formError}</p>}{error && <p role="alert" className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-sm font-semibold text-rose-700">{error}</p>}
        </div>
        <div className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-5 py-4"><button type="button" onClick={closeModal} disabled={submitting} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA] disabled:opacity-50">Hủy</button><button type="button" onClick={() => void submit()} disabled={submitting || Boolean(formError)} className="flex min-h-11 min-w-32 items-center justify-center rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] disabled:cursor-not-allowed disabled:opacity-50">{submitting ? <span className="h-5 w-5 animate-spin rounded-full border-2 border-white/30 border-t-white" aria-label="Đang xử lý" /> : modal === "close" ? "Đóng ca" : "Xác nhận"}</button></div>
      </ViewportModal>

      <ViewportModal open={Boolean(selected)} onClose={() => setSelected(null)} labelledBy="shift-detail-title" panelClassName="max-w-3xl">
        {selected && <><div className="flex items-start justify-between border-b border-[#0F2A43]/10 px-5 py-4"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#9A762F]">{statusLabel(selected.status)}</p><h2 id="shift-detail-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{selected.shiftCode}</h2><p className="mt-1 text-xs text-[#66727C]">{selected.openedByName} · {dateTime(selected.openedAtUtc)}</p></div><button type="button" onClick={() => setSelected(null)} aria-label="Đóng" className="flex h-11 w-11 items-center justify-center rounded-full text-xl text-[#66727C] hover:bg-[#F1F0EA]">×</button></div><div className="lux-scrollbar min-h-0 overflow-y-auto"><div className="grid gap-px bg-[#0F2A43]/8 sm:grid-cols-3">{[["Hệ thống", formatVnd(selected.expectedCashAmount)], ["Kiểm đếm", selected.countedCashAmount == null ? "—" : formatVnd(selected.countedCashAmount)], ["Chênh lệch", selected.varianceAmount == null ? "—" : formatVnd(selected.varianceAmount)]].map(([label, value]) => <div key={label} className="bg-white p-4"><p className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#66727C]">{label}</p><p className="mt-2 font-serif text-xl font-bold text-[#0F2A43]">{value}</p></div>)}</div>{selected.movements.length ? selected.movements.map((movement) => <MovementRow key={movement.id} movement={movement} />) : <p className="p-8 text-center text-sm text-[#66727C]">Không có phát sinh.</p>}</div></>}
      </ViewportModal>
    </main>
  );
}
