"use client";

import Link from "next/link";
import { useCallback, useEffect, useMemo, useState } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import {
  accountLabel,
  blockerLabel,
  BusinessDayClose,
  defaultBusinessDate,
  FinancialJournalEntry,
  formatVnd,
  PageResult,
  postingKindLabel,
} from "@/lib/business-day-accounting";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";

const emptyPage = <T,>(): PageResult<T> => ({
  content: [], number: 0, size: 20, totalElements: 0, totalPages: 0,
});

function unwrap<T>(response: { data?: { data?: T } | T }): T {
  const payload = response.data;
  return payload && typeof payload === "object" && "data" in payload
    ? (payload as { data: T }).data
    : payload as T;
}

const dateTime = (value?: string | null) => value
  ? new Intl.DateTimeFormat("vi-VN", {
      dateStyle: "short", timeStyle: "medium", timeZone: "Asia/Ho_Chi_Minh",
    }).format(new Date(value))
  : "—";

const dateLabel = (value: string) => new Intl.DateTimeFormat("vi-VN", {
  dateStyle: "full", timeZone: "Asia/Ho_Chi_Minh",
}).format(new Date(`${value}T12:00:00+07:00`));

function MetricCard({ label, value, hint, tone = "navy" }: {
  label: string; value: string; hint: string; tone?: "navy" | "gold" | "green" | "rose";
}) {
  const toneClass = {
    navy: "border-[#0F2A43]/12 bg-white",
    gold: "border-[#B8944F]/30 bg-[#F8F3E8]",
    green: "border-emerald-200 bg-emerald-50/70",
    rose: "border-rose-200 bg-rose-50/70",
  }[tone];
  return <article className={`rounded-2xl border p-4 shadow-[0_10px_30px_rgba(15,42,67,0.05)] ${toneClass}`}>
    <p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#74808A]">{label}</p>
    <p className="mt-2 font-serif text-2xl font-bold text-[#0F2A43]">{value}</p>
    <p className="mt-1 text-xs leading-5 text-[#66727C]">{hint}</p>
  </article>;
}

export default function BusinessDaysPage() {
  const [businessDate, setBusinessDate] = useState(() => defaultBusinessDate());
  const [preview, setPreview] = useState<BusinessDayClose | null>(null);
  const [history, setHistory] = useState<PageResult<BusinessDayClose>>(emptyPage());
  const [journal, setJournal] = useState<PageResult<FinancialJournalEntry>>(emptyPage());
  const [historyPage, setHistoryPage] = useState(0);
  const [journalPage, setJournalPage] = useState(0);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const [loading, setLoading] = useState(true);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [journalLoading, setJournalLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [closeModal, setCloseModal] = useState(false);
  const [note, setNote] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const loadPreview = useCallback(async (force = false) => {
    setLoading(true);
    try {
      const cacheBust = force ? Date.now() : 0;
      const previewResponse = await apiClient.get(`/api/admin/accounting/business-days/${businessDate}/preview${force ? `?_=${cacheBust}` : ""}`);
      setPreview(unwrap<BusinessDayClose>(previewResponse));
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể tải đối chiếu ngày nghiệp vụ"));
    } finally { setLoading(false); }
  }, [businessDate]);

  const loadHistory = useCallback(async (force = false) => {
    setHistoryLoading(true);
    try {
      const suffix = force ? `&_=${Date.now()}` : "";
      const response = await apiClient.get(`/api/admin/accounting/business-days?page=${historyPage}&size=12${suffix}`);
      setHistory(unwrap<PageResult<BusinessDayClose>>(response) || emptyPage());
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể tải lịch sử khóa ngày"));
    } finally { setHistoryLoading(false); }
  }, [historyPage]);

  const loadJournal = useCallback(async (force = false) => {
    setJournalLoading(true);
    try {
      const suffix = force ? `&_=${Date.now()}` : "";
      const response = await apiClient.get(`/api/admin/accounting/journal?businessDate=${businessDate}&page=${journalPage}&size=20${suffix}`);
      setJournal(unwrap<PageResult<FinancialJournalEntry>>(response) || emptyPage());
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể tải journal ngày nghiệp vụ"));
    } finally { setJournalLoading(false); }
  }, [businessDate, journalPage]);

  const loadAll = useCallback(async (force = false) => {
    setError("");
    await Promise.all([loadPreview(force), loadHistory(force), loadJournal(force)]);
  }, [loadHistory, loadJournal, loadPreview]);

  useEffect(() => { void loadPreview(); }, [loadPreview]);
  useEffect(() => { void loadHistory(); }, [loadHistory]);
  useEffect(() => { void loadJournal(); }, [loadJournal]);
  useEffect(() => {
    if (!notice) return;
    const timeout = window.setTimeout(() => setNotice(""), 4500);
    return () => window.clearTimeout(timeout);
  }, [notice]);

  const closeDay = async () => {
    if (!preview?.closeAllowed || preview.closed) return;
    setSubmitting(true); setError("");
    const scope = `business-day-close:${businessDate}:${note.trim()}`;
    try {
      await apiClient.post(
        `/api/admin/accounting/business-days/${businessDate}/close`,
        { note: note.trim() || undefined },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      setCloseModal(false); setNote("");
      setNotice(`Đã khóa ngày nghiệp vụ ${businessDate}. Snapshot không thể sửa hoặc xóa.`);
      await loadAll(true);
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "Không thể khóa ngày nghiệp vụ"));
    } finally { setSubmitting(false); }
  };

  const status = useMemo(() => {
    if (!preview) return null;
    if (preview.closed) return { title: "Ngày đã khóa", detail: `Khóa bởi ${preview.closedByName || "ADMIN"} lúc ${dateTime(preview.closedAtUtc)}`, className: "border-[#0F2A43]/15 bg-[#E8EDF0] text-[#0F2A43]" };
    if (preview.closeAllowed) return { title: "Đủ điều kiện khóa ngày", detail: "Journal cân bằng và không còn tác vụ vận hành bắt buộc phải xử lý.", className: "border-emerald-200 bg-emerald-50 text-emerald-900" };
    return { title: "Chưa thể khóa ngày", detail: "Xử lý các ngoại lệ bên dưới rồi bấm tải lại để đối chiếu lần cuối.", className: "border-amber-200 bg-amber-50 text-amber-900" };
  }, [preview]);

  const toggleEntry = (id: number) => setExpanded((current) => {
    const next = new Set(current);
    if (next.has(id)) next.delete(id); else next.add(id);
    return next;
  });

  return <main className="mx-auto w-full max-w-[1550px] px-4 py-6 sm:px-6 lg:px-8">
    <header className="flex flex-col gap-5 border-b border-[#0F2A43]/12 pb-6 xl:flex-row xl:items-end xl:justify-between">
      <div>
        <p className="text-[11px] font-extrabold uppercase tracking-[0.2em] text-[#9A762F]">Kế toán vận hành · ADMIN</p>
        <h1 className="mt-2 font-serif text-3xl font-bold text-[#0F2A43] sm:text-4xl">Khóa ngày & nhật ký kép</h1>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-[#66727C]">Đối chiếu tiền thực nhận, hoàn tiền, doanh thu hóa đơn và toàn bộ dòng Nợ/Có trước khi tạo snapshot bất biến.</p>
      </div>
      <div className="flex flex-wrap items-end gap-2 rounded-xl border border-[#0F2A43]/12 bg-white p-3 shadow-sm">
        <label htmlFor="business-date" className="grid gap-1 text-xs font-bold text-[#0F2A43]">Ngày nghiệp vụ
          <input id="business-date" type="date" value={businessDate} onChange={(event) => { setBusinessDate(event.target.value); setJournalPage(0); setExpanded(new Set()); }} className="min-h-11 rounded-lg border border-[#0F2A43]/18 bg-[#FBFAF7] px-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" />
        </label>
        <button type="button" onClick={() => void loadAll(true)} disabled={loading || historyLoading || journalLoading} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA] disabled:opacity-50">{loading || historyLoading || journalLoading ? "Đang tải…" : "Tải lại"}</button>
        <button type="button" onClick={() => { setError(""); setCloseModal(true); }} disabled={!preview?.closeAllowed || preview?.closed || loading} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:-translate-y-0.5 hover:bg-[#173D5F] disabled:cursor-not-allowed disabled:opacity-45">Khóa ngày</button>
      </div>
    </header>

    {notice && <div role="status" className="mt-5 rounded-xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm font-semibold text-emerald-900">{notice}</div>}
    {error && !closeModal && <div role="alert" className="mt-5 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-800">{error}</div>}

    {loading && !preview ? <section className="mt-6 grid gap-4 sm:grid-cols-2 xl:grid-cols-4" aria-label="Đang tải dữ liệu">{[1,2,3,4].map((item) => <div key={item} className="h-32 animate-pulse rounded-2xl bg-[#0F2A43]/7" />)}</section> : preview && <>
      {status && <section className={`mt-6 rounded-2xl border px-5 py-4 ${status.className}`}><div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><div><p className="font-bold">{status.title}</p><p className="mt-1 text-sm opacity-80">{status.detail}</p></div><span className="text-xs font-bold uppercase tracking-[0.14em]">{dateLabel(businessDate)}</span></div></section>}
      <section className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <MetricCard label="Tiền đã nhận" value={formatVnd(preview.paymentReceivedAmount)} hint="Tiền mặt và tài khoản SePay" tone="green" />
        <MetricCard label="Hoàn tiền hoàn tất" value={formatVnd(preview.refundCompletedAmount)} hint="Dòng tiền thực tế đã chi" tone="rose" />
        <MetricCard label="Doanh thu ghi nhận" value={formatVnd(preview.recognizedRevenueAmount)} hint="Theo snapshot hóa đơn bất biến" tone="gold" />
        <MetricCard label="Journal" value={`${preview.journalEntryCount} bút toán`} hint={`Tổng Nợ = Tổng Có = ${formatVnd(preview.totalDebit)}`} />
      </section>

      <section className="mt-4 grid gap-4 lg:grid-cols-[1.35fr_1fr]">
        <article className="rounded-2xl border border-[#0F2A43]/10 bg-white p-5 shadow-[0_12px_35px_rgba(15,42,67,0.05)]">
          <div className="flex items-center justify-between gap-3"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#9A762F]">Điều kiện khóa</p><h2 className="mt-1 font-serif text-xl font-bold text-[#0F2A43]">Ngoại lệ cần xử lý</h2></div><span className={`rounded-full px-3 py-1 text-xs font-bold ${preview.blockers.length ? "bg-amber-100 text-amber-800" : "bg-emerald-100 text-emerald-800"}`}>{preview.blockers.length || "Không có"}</span></div>
          {preview.blockers.length ? <ul className="mt-4 grid gap-2">{preview.blockers.map((blocker) => <li key={blocker} className="flex items-start gap-3 rounded-xl border border-amber-200/80 bg-amber-50/70 px-4 py-3 text-sm font-semibold text-amber-950"><span aria-hidden="true">!</span><span>{blockerLabel(blocker)}</span></li>)}</ul> : <p className="mt-4 rounded-xl bg-emerald-50 px-4 py-4 text-sm leading-6 text-emerald-900">Không còn ca mở, event SePay chưa xử lý, nguồn chưa journal hoặc chênh lệch Nợ/Có.</p>}
        </article>
        <article className="rounded-2xl border border-[#0F2A43]/10 bg-[#F8F6F0] p-5">
          <p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#9A762F]">Số dư kiểm soát</p><h2 className="mt-1 font-serif text-xl font-bold text-[#0F2A43]">Nghĩa vụ còn theo dõi</h2>
          <dl className="mt-4 grid gap-3 text-sm">
            <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/8 pb-3"><dt className="text-[#66727C]">{preview.closed ? "Nghĩa vụ hoàn tại lúc khóa" : "Nghĩa vụ hoàn đang mở hiện tại"}</dt><dd className="font-bold text-[#0F2A43]">{formatVnd(preview.pendingRefundPayableAmount)}</dd></div>
            <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/8 pb-3"><dt className="text-[#66727C]">Tiền chưa xác định</dt><dd className={`font-bold ${preview.unreconciledFundsBalance ? "text-rose-700" : "text-emerald-700"}`}>{formatVnd(preview.unreconciledFundsBalance)}</dd></div>
            <div className="flex items-center justify-between gap-4"><dt className="text-[#66727C]">Chênh lệch tiền mặt</dt><dd className={`font-bold ${preview.cashVarianceAmount ? "text-rose-700" : "text-[#0F2A43]"}`}>{formatVnd(preview.cashVarianceAmount)}</dd></div>
          </dl>
        </article>
      </section>
    </>}

    <section className="mt-8">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#9A762F]">Dấu vết tài chính</p><h2 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Journal ngày {businessDate}</h2></div><p className="text-xs text-[#66727C]">{journal.totalElements} bút toán · bấm từng dòng để xem Nợ/Có</p></div>
      <div className="mt-4 overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white">
        {journalLoading ? <div className="flex min-h-36 items-center justify-center gap-2 px-6 py-12 text-sm font-semibold text-[#66727C]"><span className="h-4 w-4 animate-spin rounded-full border-2 border-[#0F2A43]/20 border-t-[#0F2A43]" aria-hidden="true" />Đang tải journal…</div> : journal.content.length ? journal.content.map((entry) => {
          const open = expanded.has(entry.id);
          return <article key={entry.id} className="border-b border-[#0F2A43]/8 last:border-b-0">
            <button type="button" onClick={() => toggleEntry(entry.id)} aria-expanded={open} className="grid min-h-16 w-full gap-3 px-4 py-4 text-left transition hover:bg-[#F8F6F0] sm:grid-cols-[1.1fr_1.5fr_1fr_auto] sm:items-center sm:px-5">
              <div><p className="font-bold text-[#0F2A43]">{entry.entryNumber}</p><p className="mt-1 text-xs text-[#66727C]">{dateTime(entry.postedAtUtc)}</p></div>
              <div><div className="flex flex-wrap items-center gap-2"><p className="font-semibold text-[#0F2A43]">{postingKindLabel(entry.postingKind)}</p>{entry.latePosting && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-extrabold text-amber-800">GHI NHẬN MUỘN</span>}</div><p className="mt-1 text-xs text-[#66727C]">{entry.description}</p></div>
              <div><p className="text-xs text-[#66727C]">Tổng Nợ / Có</p><p className="mt-1 font-bold text-[#0F2A43]">{formatVnd(entry.totalDebit)}</p></div>
              <span className={`flex h-9 w-9 items-center justify-center rounded-full border border-[#0F2A43]/15 text-[#0F2A43] transition ${open ? "rotate-180 bg-[#F1F0EA]" : ""}`} aria-hidden="true">⌄</span>
            </button>
            {open && <div className="border-t border-[#0F2A43]/8 bg-[#FBFAF7] px-4 py-4 sm:px-5">
              <div className="mb-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-[#66727C]"><span>Nguồn: <b className="text-[#0F2A43]">{entry.sourceType} · {entry.sourceId}</b></span>{entry.reservationCode && <Link href={`/dashboard/reservations?reservationCode=${encodeURIComponent(entry.reservationCode)}`} className="font-bold text-[#8E6B2E] hover:text-[#0F2A43]">Đơn {entry.reservationCode} →</Link>}{entry.latePosting && <span>Ngày gốc: <b>{entry.originalBusinessDate}</b></span>}</div>
              <div className="overflow-x-auto"><table className="w-full min-w-[620px] text-sm"><thead><tr className="border-b border-[#0F2A43]/10 text-left text-[10px] uppercase tracking-[0.12em] text-[#66727C]"><th className="px-3 py-2">#</th><th className="px-3 py-2">Tài khoản</th><th className="px-3 py-2">Diễn giải</th><th className="px-3 py-2 text-right">Nợ</th><th className="px-3 py-2 text-right">Có</th></tr></thead><tbody>{entry.lines.map((line) => <tr key={`${entry.id}-${line.lineNumber}`} className="border-b border-[#0F2A43]/6 last:border-0"><td className="px-3 py-3 text-[#66727C]">{line.lineNumber}</td><td className="px-3 py-3 font-bold text-[#0F2A43]">{accountLabel(line.accountCode)}</td><td className="px-3 py-3 text-[#66727C]">{line.description || "—"}</td><td className="px-3 py-3 text-right font-bold text-[#0F2A43]">{line.direction === "DEBIT" ? formatVnd(line.amount) : "—"}</td><td className="px-3 py-3 text-right font-bold text-[#0F2A43]">{line.direction === "CREDIT" ? formatVnd(line.amount) : "—"}</td></tr>)}</tbody></table></div>
            </div>}
          </article>;
        }) : <div className="px-6 py-12 text-center"><p className="font-serif text-xl font-bold text-[#0F2A43]">Chưa có bút toán trong ngày</p><p className="mt-2 text-sm text-[#66727C]">Payment, refund và invoice hoàn tất sẽ được ghi tự động; ADMIN không nhập journal tùy ý.</p></div>}
      </div>
      {journal.totalPages > 1 && <nav aria-label="Phân trang journal" className="mt-4 flex items-center justify-end gap-3"><button type="button" disabled={journal.number <= 0 || journalLoading} onClick={() => setJournalPage((page) => Math.max(0, page - 1))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold disabled:opacity-45">← Trước</button><span className="text-xs font-semibold text-[#66727C]">{journal.number + 1}/{journal.totalPages}</span><button type="button" disabled={journal.number >= journal.totalPages - 1 || journalLoading} onClick={() => setJournalPage((page) => page + 1)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold disabled:opacity-45">Sau →</button></nav>}
    </section>

    <section className="mt-8"><div className="flex items-end justify-between"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#9A762F]">Snapshot bất biến</p><h2 className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Các ngày đã khóa</h2></div><span className="text-xs text-[#66727C]">{history.totalElements} ngày</span></div><div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-3">{history.content.map((item) => <button key={item.id} type="button" onClick={() => { setBusinessDate(item.businessDate); setJournalPage(0); window.scrollTo({ top: 0, behavior: "smooth" }); }} className="rounded-2xl border border-[#0F2A43]/10 bg-white p-4 text-left transition hover:-translate-y-0.5 hover:border-[#B8944F] hover:shadow-lg"><div className="flex items-start justify-between gap-3"><div><p className="font-serif text-lg font-bold text-[#0F2A43]">{item.businessDate}</p><p className="mt-1 text-xs text-[#66727C]">{item.closedByName} · {dateTime(item.closedAtUtc)}</p></div><span className="rounded-full bg-[#E8EDF0] px-2.5 py-1 text-[10px] font-extrabold text-[#0F2A43]">ĐÃ KHÓA</span></div><p className="mt-4 text-sm text-[#66727C]">Doanh thu <b className="text-[#0F2A43]">{formatVnd(item.recognizedRevenueAmount)}</b> · {item.journalEntryCount} bút toán</p></button>)}</div>{!history.content.length && !historyLoading && <p className="mt-4 rounded-2xl border border-dashed border-[#0F2A43]/18 px-5 py-8 text-center text-sm text-[#66727C]">Chưa có ngày nghiệp vụ nào được khóa.</p>}{history.totalPages > 1 && <nav aria-label="Phân trang ngày đã khóa" className="mt-4 flex items-center justify-end gap-3"><button type="button" disabled={history.number <= 0 || historyLoading} onClick={() => setHistoryPage((page) => Math.max(0, page - 1))} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold disabled:opacity-45">← Trước</button><span className="text-xs font-semibold text-[#66727C]">{history.number + 1}/{history.totalPages}</span><button type="button" disabled={history.number >= history.totalPages - 1 || historyLoading} onClick={() => setHistoryPage((page) => page + 1)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-4 text-sm font-bold disabled:opacity-45">Sau →</button></nav>}</section>

    <ViewportModal open={closeModal} onClose={() => { if (!submitting) setCloseModal(false); }} labelledBy="close-business-day-title" busy={submitting} panelClassName="max-w-lg">
      <div className="flex items-start justify-between border-b border-[#0F2A43]/10 px-5 py-4"><div><p className="text-[10px] font-extrabold uppercase tracking-[0.16em] text-[#9A762F]">Thao tác bất biến</p><h2 id="close-business-day-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">Khóa ngày {businessDate}</h2></div><button type="button" onClick={() => setCloseModal(false)} disabled={submitting} aria-label="Đóng" className="flex h-11 w-11 items-center justify-center rounded-full text-xl text-[#66727C] transition hover:bg-[#F1F0EA] disabled:opacity-40">×</button></div>
      <div className="lux-scrollbar min-h-0 overflow-y-auto px-5 py-5"><div className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-950">Sau khi xác nhận, snapshot của ngày không thể sửa hoặc xóa. Giao dịch SePay đến muộn sẽ được ghi vào ngày đang mở và giữ ngày gốc để đối soát.</div><label htmlFor="close-note" className="mt-4 block text-sm font-bold text-[#0F2A43]">Ghi chú chốt ngày</label><textarea id="close-note" data-modal-autofocus value={note} onChange={(event) => setNote(event.target.value)} maxLength={1000} rows={4} placeholder="Ví dụ: Đã kiểm tra ca thu ngân và event SePay..." className="mt-2 w-full rounded-lg border border-[#0F2A43]/18 px-4 py-3 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20" />{error && <p role="alert" className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-sm font-semibold text-rose-700">{error}</p>}</div>
      <div className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-5 py-4"><button type="button" onClick={() => setCloseModal(false)} disabled={submitting} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-sm font-bold text-[#0F2A43] hover:bg-[#F1F0EA] disabled:opacity-50">Hủy</button><button type="button" onClick={() => void closeDay()} disabled={submitting || !preview?.closeAllowed} className="flex min-h-11 min-w-36 items-center justify-center rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#173D5F] disabled:opacity-50">{submitting ? <span className="h-5 w-5 animate-spin rounded-full border-2 border-white/30 border-t-white" aria-label="Đang khóa ngày" /> : "Xác nhận khóa"}</button></div>
    </ViewportModal>
  </main>;
}
