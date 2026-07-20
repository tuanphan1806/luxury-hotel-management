"use client";

import { useCallback, useEffect, useState } from "react";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { apiClient } from "@/lib/api";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";

interface ReconciliationSnapshot {
  requiredAmount: number;
  acceptedAmount: number;
  outstandingAmount: number;
  deltaAmount: number;
  reservedRefundAmount: number;
  uncoveredRefundAmount: number;
  blockingReasons: string[];
}

interface ReconciliationRequest {
  id: number;
  reservationId: number;
  reservationCode: string;
  status: "PENDING" | "APPROVED" | "REJECTED" | "CANCELLED";
  mismatchSnapshot: ReconciliationSnapshot;
  reasonCode: string;
  reasonNote: string;
  requestedByName: string;
  requestedByRole: string;
  createdAtUtc: string;
}

interface PageResult<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  first: boolean;
  last: boolean;
}

function unwrap<T>(response: { data?: { data?: T } | T }): T {
  const payload = response.data;
  return payload && typeof payload === "object" && "data" in payload
    ? (payload as { data: T }).data : payload as T;
}

function formatVND(value?: number) {
  return `${Number(value || 0).toLocaleString("vi-VN")} đ`;
}

function errorMessage(error: unknown, fallback: string) {
  if (error && typeof error === "object" && "response" in error) {
    const response = (error as { response?: { data?: { message?: string } } }).response;
    if (response?.data?.message) return response.data.message;
  }
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function ReconciliationRequestsPage() {
  const { localize } = useLanguage();
  const { role, isAdmin } = useDashboardRole();
  const [requests, setRequests] = useState<PageResult<ReconciliationRequest> | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<ReconciliationRequest | null>(null);
  const [resolution, setResolution] = useState({
    approve: true,
    correctionType: "FEE_CORRECTION" as "FEE_CORRECTION" | "LINK_EXISTING_PAYMENT",
    correctedAdditionalFee: "0",
    paymentProviderEventId: "",
    paymentTransactionId: "",
    reasonCode: "VERIFIED_TECHNICAL_CORRECTION",
    note: "",
  });
  const [resolutionError, setResolutionError] = useState("");

  const loadRequests = useCallback(async () => {
    if (!isAdmin) return;
    setLoading(true); setError("");
    try {
      const response = await apiClient.get(`/api/admin/checkout-reconciliation-requests?status=PENDING&page=${page}&size=20`);
      setRequests(unwrap<PageResult<ReconciliationRequest>>(response));
    } catch (requestError: unknown) {
      setError(errorMessage(requestError, localize("Không thể tải hàng đợi đối soát.", "Unable to load reconciliation queue.")));
    } finally { setLoading(false); }
  }, [isAdmin, localize, page]);

  useEffect(() => { void loadRequests(); }, [loadRequests]);

  const openResolution = (request: ReconciliationRequest) => {
    setSelected(request);
    setResolution({
      approve: true,
      correctionType: "FEE_CORRECTION",
      correctedAdditionalFee: "0",
      paymentProviderEventId: "",
      paymentTransactionId: "",
      reasonCode: "VERIFIED_TECHNICAL_CORRECTION",
      note: "",
    });
    setResolutionError("");
  };

  const submitResolution = async () => {
    if (!selected || !resolution.note.trim()) {
      setResolutionError(localize("Bắt buộc ghi rõ căn cứ xử lý.", "A resolution note is required."));
      return;
    }
    const payload: Record<string, unknown> = {
      approve: resolution.approve,
      reasonCode: resolution.reasonCode,
      note: resolution.note.trim(),
    };
    if (resolution.approve) {
      payload.correctionType = resolution.correctionType;
      if (resolution.correctionType === "FEE_CORRECTION") {
        const amount = Number(resolution.correctedAdditionalFee.replace(/[^0-9]/g, ""));
        if (!Number.isSafeInteger(amount) || amount < 0) {
          setResolutionError(localize("Phụ phí mới phải là số VND nguyên không âm.", "The corrected fee must be a non-negative whole-VND amount."));
          return;
        }
        payload.correctedAdditionalFee = amount;
      } else {
        if (!resolution.paymentProviderEventId.trim() || !resolution.paymentTransactionId.trim()) {
          setResolutionError(localize("Phải chọn event SePay có thật và payment cần liên kết.", "Select a real SePay event and the target payment."));
          return;
        }
        payload.paymentProviderEventId = resolution.paymentProviderEventId.trim();
        payload.paymentTransactionId = resolution.paymentTransactionId.trim();
      }
    }
    const scope = `checkout-reconciliation:${selected.id}:resolve`;
    setLoading(true); setResolutionError("");
    try {
      await apiClient.patch(`/api/admin/checkout-reconciliation-requests/${selected.id}/resolve`, payload, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) },
      });
      clearIdempotencyKey(scope);
      setSelected(null);
      await loadRequests();
    } catch (requestError: unknown) {
      setResolutionError(errorMessage(requestError, localize("Không thể xử lý yêu cầu.", "Unable to resolve the request.")));
    } finally { setLoading(false); }
  };

  if (role && !isAdmin) return <main className="p-6"><div className="rounded-xl border border-amber-200 bg-amber-50 p-6 text-amber-900"><h1 className="text-xl font-bold">{localize("Khu vực chỉ dành cho ADMIN", "ADMIN-only area")}</h1></div></main>;

  return <main className="space-y-5 p-4 sm:p-6">
    <header className="rounded-xl bg-[#0F2A43] px-6 py-5 text-white"><p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D7B66D]">{localize("Ngoại lệ có kiểm soát · Chỉ ADMIN", "Controlled exceptions · ADMIN only")}</p><h1 className="mt-2 font-serif text-3xl font-bold">{localize("Yêu cầu xử lý đối soát", "Reconciliation requests")}</h1><p className="mt-2 max-w-3xl text-sm text-white/75">{localize("Chỉ sửa dữ liệu qua nghiệp vụ hợp lệ. Duyệt yêu cầu không tự checkout và không xóa công nợ.", "Corrections must use valid business operations. Approval never checks out a stay or waives debt.")}</p></header>

    {error && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm font-semibold text-rose-700">{error}</p>}
    <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white shadow-sm"><div className="flex items-center justify-between border-b border-[#0F2A43]/10 px-5 py-4"><div><h2 className="font-bold text-[#0F2A43]">{localize("Đang chờ ADMIN", "Pending ADMIN review")}</h2><p className="text-xs text-[#66727C]">{requests?.totalElements || 0} {localize("yêu cầu", "requests")}</p></div><button type="button" disabled={loading} onClick={() => void loadRequests()} className="min-h-10 rounded-lg border px-4 text-xs font-bold text-[#0F2A43] disabled:opacity-50">{localize("Tải lại", "Refresh")}</button></div>
      {loading && !requests ? <div className="space-y-3 p-5">{[1, 2, 3].map((item) => <div key={item} className="h-28 animate-pulse rounded-xl bg-[#F1F0EA]" />)}</div> : <div className="grid gap-4 p-4 xl:grid-cols-2">{(requests?.content || []).map((request) => <article key={request.id} className="rounded-xl border border-amber-200 bg-amber-50/50 p-5"><div className="flex flex-wrap items-start justify-between gap-3"><div><p className="font-mono text-xs font-bold text-[#80632F]">{request.reservationCode || `#${request.reservationId}`}</p><h3 className="mt-1 text-lg font-bold text-[#0F2A43]">{request.reasonCode}</h3><p className="mt-1 text-xs text-[#66727C]">{request.requestedByName} · {request.requestedByRole} · {new Date(request.createdAtUtc).toLocaleString("vi-VN")}</p></div><span className="rounded-full bg-amber-700 px-3 py-1 text-[10px] font-black text-white">PENDING</span></div><p className="mt-3 text-sm leading-6 text-[#0F2A43]">{request.reasonNote}</p><div className="mt-4 grid grid-cols-2 gap-3 rounded-lg bg-white p-3 text-sm"><div><p className="text-xs text-[#66727C]">{localize("Đã thu", "Collected")}</p><p className="font-bold tabular-nums">{formatVND(request.mismatchSnapshot.acceptedAmount)}</p></div><div className="text-right"><p className="text-xs text-[#66727C]">{localize("Cần thu", "Required")}</p><p className="font-bold tabular-nums">{formatVND(request.mismatchSnapshot.requiredAmount)}</p></div></div><ul className="mt-3 list-disc space-y-1 pl-5 text-xs text-amber-900">{request.mismatchSnapshot.blockingReasons.map((reason) => <li key={reason}>{reason}</li>)}</ul><div className="mt-4 text-right"><button type="button" onClick={() => openResolution(request)} className="min-h-10 rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white hover:bg-[#091E30]">{localize("Kiểm tra & xử lý", "Review & resolve")}</button></div></article>)}</div>}
      {!loading && !requests?.content?.length && <p className="px-6 py-16 text-center text-sm text-[#66727C]">{localize("Không có yêu cầu đối soát đang chờ.", "No reconciliation requests are pending.")}</p>}
      {requests && requests.totalPages > 1 && <div className="flex items-center justify-between border-t px-5 py-4 text-xs"><span>{localize("Trang", "Page")} {requests.number + 1}/{requests.totalPages}</span><div className="flex gap-2"><button type="button" disabled={requests.first || loading} onClick={() => setPage((value) => Math.max(0, value - 1))} className="min-h-9 rounded-lg border px-3 font-bold disabled:opacity-40">{localize("Trước", "Previous")}</button><button type="button" disabled={requests.last || loading} onClick={() => setPage((value) => value + 1)} className="min-h-9 rounded-lg border px-3 font-bold disabled:opacity-40">{localize("Sau", "Next")}</button></div></div>}
    </section>

    <ViewportModal open={Boolean(selected)} onClose={() => setSelected(null)} labelledBy="reconciliation-resolution-title" busy={loading} panelClassName="max-w-xl">
      {selected && <section className="flex min-h-0 flex-1 flex-col"><header className="border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-6 py-5"><p className="text-xs font-bold uppercase text-[#80632F]">{selected.reservationCode}</p><h2 id="reconciliation-resolution-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Xử lý yêu cầu đối soát", "Resolve reconciliation request")}</h2></header><div className="lux-scrollbar min-h-0 flex-1 space-y-4 overflow-y-auto px-6 py-5"><fieldset><legend className="text-sm font-bold text-[#0F2A43]">{localize("Quyết định", "Decision")}</legend><div className="mt-2 grid grid-cols-2 gap-2"><label className={`rounded-lg border p-3 text-sm font-bold ${resolution.approve ? "border-emerald-500 bg-emerald-50" : ""}`}><input type="radio" checked={resolution.approve} onChange={() => setResolution((current) => ({ ...current, approve: true }))} className="mr-2" />{localize("Điều chỉnh", "Correct")}</label><label className={`rounded-lg border p-3 text-sm font-bold ${!resolution.approve ? "border-rose-500 bg-rose-50" : ""}`}><input type="radio" checked={!resolution.approve} onChange={() => setResolution((current) => ({ ...current, approve: false }))} className="mr-2" />{localize("Từ chối", "Reject")}</label></div></fieldset>{resolution.approve && <><label className="block text-sm font-bold text-[#0F2A43]">{localize("Cách sửa", "Correction") } *<select value={resolution.correctionType} onChange={(event) => setResolution((current) => ({ ...current, correctionType: event.target.value as typeof current.correctionType }))} className="mt-2 min-h-11 w-full rounded-lg border bg-white px-3 text-sm"><option value="FEE_CORRECTION">{localize("Sửa phụ phí checkout", "Correct checkout fee")}</option><option value="LINK_EXISTING_PAYMENT">{localize("Liên kết event SePay có thật", "Link an existing SePay event")}</option></select></label>{resolution.correctionType === "FEE_CORRECTION" ? <label className="block text-sm font-bold text-[#0F2A43]">{localize("Phụ phí đúng (VND)", "Correct fee (VND)")} *<input inputMode="numeric" value={resolution.correctedAdditionalFee} onChange={(event) => setResolution((current) => ({ ...current, correctedAdditionalFee: event.target.value.replace(/[^0-9]/g, "") }))} className="mt-2 min-h-11 w-full rounded-lg border px-3 text-right text-lg font-bold" /></label> : <div className="grid gap-3"><label className="block text-sm font-bold text-[#0F2A43]">SePay provider event ID *<input value={resolution.paymentProviderEventId} onChange={(event) => setResolution((current) => ({ ...current, paymentProviderEventId: event.target.value }))} className="mt-2 min-h-11 w-full rounded-lg border px-3 font-mono text-sm" /></label><label className="block text-sm font-bold text-[#0F2A43]">Payment transaction ID *<input value={resolution.paymentTransactionId} onChange={(event) => setResolution((current) => ({ ...current, paymentTransactionId: event.target.value }))} className="mt-2 min-h-11 w-full rounded-lg border px-3 font-mono text-sm" /></label></div>}</>}<label className="block text-sm font-bold text-[#0F2A43]">{localize("Mã lý do", "Reason code")} *<select value={resolution.reasonCode} onChange={(event) => setResolution((current) => ({ ...current, reasonCode: event.target.value }))} className="mt-2 min-h-11 w-full rounded-lg border bg-white px-3 text-sm"><option value="VERIFIED_TECHNICAL_CORRECTION">{localize("Đã xác minh lỗi kỹ thuật", "Verified technical correction")}</option><option value="INCORRECT_FEE_ENTRY">{localize("Sửa phụ phí nhập sai", "Incorrect fee entry")}</option><option value="PROVIDER_EVENT_RECOVERED">{localize("Khôi phục event provider", "Provider event recovered")}</option><option value="INSUFFICIENT_EVIDENCE">{localize("Không đủ căn cứ", "Insufficient evidence")}</option></select></label><label className="block text-sm font-bold text-[#0F2A43]">{localize("Căn cứ xử lý", "Resolution note")} *<textarea rows={4} maxLength={1000} value={resolution.note} onChange={(event) => { setResolution((current) => ({ ...current, note: event.target.value })); setResolutionError(""); }} className="mt-2 w-full resize-none rounded-lg border px-3 py-2 text-sm" /></label>{resolutionError && <p role="alert" className="rounded-lg bg-rose-50 p-3 text-sm font-semibold text-rose-700">{resolutionError}</p>}<p className="rounded-lg border border-sky-200 bg-sky-50 p-3 text-xs leading-5 text-sky-900">{localize("Backend sẽ chạy lại đối soát. Nếu chưa MATCHED, toàn bộ điều chỉnh bị rollback và yêu cầu vẫn PENDING. Sau khi duyệt vẫn phải bấm Checkout ở màn hình reservation.", "The backend re-runs reconciliation. If it is not MATCHED, the correction rolls back and the request stays PENDING. Checkout remains a separate reservation action.")}</p></div><footer className="flex flex-col-reverse gap-3 border-t px-6 py-4 sm:flex-row sm:justify-end"><button type="button" disabled={loading} onClick={() => setSelected(null)} className="min-h-11 rounded-lg border px-5 text-sm font-bold">{localize("Đóng", "Close")}</button><button type="button" disabled={loading || !resolution.note.trim()} onClick={() => void submitResolution()} className={`min-h-11 rounded-lg px-5 text-sm font-bold text-white disabled:opacity-50 ${resolution.approve ? "bg-emerald-700" : "bg-rose-700"}`}>{loading ? localize("Đang xử lý...", "Processing...") : resolution.approve ? localize("Áp dụng điều chỉnh", "Apply correction") : localize("Từ chối yêu cầu", "Reject request")}</button></footer></section>}
    </ViewportModal>
  </main>;
}
