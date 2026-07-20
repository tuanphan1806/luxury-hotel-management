"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiClient } from "@/lib/api";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";

type AuditRisk = "NORMAL" | "MEDIUM" | "HIGH" | "CRITICAL";

interface AuditLog {
  id: number;
  reservationId?: number;
  reservationCode?: string;
  targetType?: string;
  targetId?: string;
  action: string;
  actorName: string;
  actorRole: string;
  details?: string;
  oldValue?: Record<string, unknown>;
  newValue?: Record<string, unknown>;
  detail?: Record<string, unknown>;
  riskLevel: AuditRisk;
  category?: string;
  correlationId?: string;
  occurredAtUtc: string;
}

interface PageResult<T> {
  content: T[];
  number: number;
  totalPages: number;
  totalElements: number;
  first: boolean;
  last: boolean;
}

interface MonitoringSummary {
  generatedAtUtc: string;
  durableSePayEventsToday: number;
  providerEventsRetrying: number;
  paymentEventsReviewRequired: number;
  unmatchedIncomingTransfers: number;
  unmatchedOutgoingRefundTransfers: number;
  overdueActiveRoomHolds: number;
  stalePendingRefunds: number;
  pendingCheckoutExceptionRequests: number;
  stalePendingCheckoutExceptionRequests: number;
  highRiskActionsLast24Hours: number;
  pendingEmailAlerts: number;
  failedEmailAlerts: number;
  staleEmailAlerts: number;
  webhookAuthenticationFailuresInWindow: number;
  sePayReconciliationStatus: "HEALTHY" | "FAILED" | "STALE" | "NEVER_RUN";
  sePayReconciliationLastRunAtUtc?: string;
  refundPendingThresholdMinutes: number;
  reconciliationStaleThresholdMinutes: number;
  checkoutPendingThresholdMinutes: number;
  roomHoldOverdueGraceMinutes: number;
  emailOutboxStaleThresholdMinutes: number;
  webhookAuthenticationFailureThresholdCount: number;
  webhookAuthenticationFailureWindowMinutes: number;
}

const ACTION_LABELS: Record<string, string> = {
  CONFIRM: "Xác nhận đặt phòng", CHECK_IN: "Nhận phòng", CHECK_OUT: "Trả phòng",
  CANCEL: "Hủy đặt phòng", MARK_NO_SHOW: "Ghi nhận khách không đến",
  UPDATE_CHECKOUT_FEE: "Điều chỉnh phụ phí checkout", REFUND: "Xử lý hoàn tiền",
  PAYMENT_RECEIVED: "Ghi nhận tiền vào", PROVIDER_EVENT_MATCHED: "Đối chiếu giao dịch SePay",
  PROVIDER_EVENT_IGNORED: "Bỏ qua giao dịch chưa xác định",
  PROVIDER_EVENT_REFUND_CREATED: "Tạo nghĩa vụ hoàn từ giao dịch",
  ROOM_HOLD_RELEASED_MANUALLY: "Nhả giữ phòng thủ công",
  ROOM_HOLD_AUTO_EXPIRED: "Giữ phòng tự hết hạn",
  RESERVATION_AUTO_CANCELLED: "Hệ thống tự hủy reservation",
  PAYMENT_MARKED_PAID_MANUALLY: "ADMIN khôi phục đối soát payment",
  PRICE_OVERRIDDEN: "Thay giá walk-in",
  CHECKOUT_RECONCILIATION_REQUESTED: "Yêu cầu xử lý lệch checkout",
  CHECKOUT_RECONCILIATION_REJECTED: "Từ chối yêu cầu lệch checkout",
  CHECKOUT_RECONCILIATION_PASSED: "Đối soát checkout đã khớp",
  CHECKOUT_RECONCILIATION_OVERRIDDEN: "ADMIN duyệt điều chỉnh đối soát",
  USER_ROLE_CHANGED: "Thay đổi vai trò người dùng",
  ROOM_CREATED: "Tạo phòng", ROOM_UPDATED: "Cập nhật phòng", ROOM_DELETED: "Xóa phòng",
  ROOM_TYPE_CREATED: "Tạo hạng phòng", ROOM_TYPE_UPDATED: "Cập nhật hạng phòng", ROOM_TYPE_DELETED: "Xóa hạng phòng",
  FACILITY_CREATED: "Tạo tiện ích", FACILITY_UPDATED: "Cập nhật tiện ích", FACILITY_DELETED: "Xóa tiện ích",
  GALLERY_CREATED: "Tạo ảnh thư viện", GALLERY_UPDATED: "Cập nhật ảnh thư viện", GALLERY_DELETED: "Xóa ảnh thư viện",
  LOGIN_SUCCESS: "Đăng nhập thành công", LOGOUT: "Đăng xuất",
  PASSWORD_CHANGED: "Đổi mật khẩu", PASSWORD_RESET_COMPLETED: "Hoàn tất đặt lại mật khẩu",
  PASSWORD_RESET_BY_ADMIN: "ADMIN đặt lại mật khẩu",
  PROVIDER_EVENT_REVIEW_REQUIRED: "Event SePay cần đối soát",
  WEBHOOK_AUTHENTICATION_REJECTED: "Webhook SePay sai xác thực vượt ngưỡng",
};

const FIELD_LABELS: Record<string, string> = {
  status: "Trạng thái", eventStatus: "Trạng thái event", paymentStatus: "Trạng thái payment",
  reservationStatus: "Trạng thái reservation", amount: "Số tiền", unitPrice: "Đơn giá",
  subtotal: "Thành tiền", totalAmount: "Tổng tiền", requiredAmount: "Cần thu",
  acceptedAmount: "Đã thu", deltaAmount: "Chênh lệch", reasonCode: "Mã lý do",
  reason: "Lý do", note: "Ghi chú", quantity: "Số lượng", roomTypeName: "Hạng phòng",
  originalAmount: "Tiền gốc theo ledger", penaltyAmount: "Tiền phạt hủy",
  penaltyPercent: "Tỷ lệ phạt", refundAmount: "Số tiền hoàn", policyApplied: "Chính sách áp dụng",
  policyNote: "Căn cứ chính sách", failureCount: "Số lần thất bại", thresholdCount: "Ngưỡng cảnh báo",
  windowMinutes: "Cửa sổ theo dõi (phút)", method: "Phương thức",
};

function unwrap<T>(response: { data?: { data?: T } | T }): T {
  const payload = response.data;
  return payload && typeof payload === "object" && "data" in payload
    ? (payload as { data: T }).data : payload as T;
}

function formatTime(value?: string) {
  if (!value) return "-";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString("vi-VN");
}

function formatValue(field: string, value: unknown): string {
  if (value === null || value === undefined || value === "") return "—";
  if (typeof value === "boolean") return value ? "Có" : "Không";
  if (typeof value === "number") {
    const rendered = value.toLocaleString("vi-VN");
    return /(amount|price|subtotal|fee|adjustment)$/i.test(field) ? `${rendered} đ` : rendered;
  }
  if (Array.isArray(value)) return value.map((item) => formatValue(field, item)).join("; ");
  if (typeof value === "object") return Object.entries(value as Record<string, unknown>)
    .map(([key, item]) => `${FIELD_LABELS[key] || key}: ${formatValue(key, item)}`).join(" · ");
  return String(value);
}

function riskClass(risk: AuditRisk) {
  if (risk === "CRITICAL") return "border-rose-300 bg-rose-100 text-rose-800";
  if (risk === "HIGH") return "border-orange-300 bg-orange-100 text-orange-800";
  if (risk === "MEDIUM") return "border-amber-200 bg-amber-50 text-amber-800";
  return "border-[#0F2A43]/10 bg-[#F1F0EA] text-[#66727C]";
}

export default function AuditLogsPage() {
  const { localize } = useLanguage();
  const { role, isAdmin } = useDashboardRole();
  const [logs, setLogs] = useState<PageResult<AuditLog> | null>(null);
  const [summary, setSummary] = useState<MonitoringSummary | null>(null);
  const [selected, setSelected] = useState<AuditLog | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [actors, setActors] = useState<string[]>([]);
  const [filters, setFilters] = useState({
    targetType: "",
    targetId: "",
    actor: "",
    actorRole: "",
    action: "",
    category: "",
    riskLevel: "",
    from: "",
    to: "",
  });

  const queryString = useMemo(() => {
    const params = new URLSearchParams({ page: String(page), size: "25" });
    Object.entries(filters).forEach(([key, value]) => {
      if (!value) return;
      params.set(key, key === "from" || key === "to" ? new Date(value).toISOString() : value);
    });
    return params.toString();
  }, [filters, page]);

  const loadLogs = useCallback(async () => {
    if (!isAdmin) return;
    setLoading(true); setError("");
    try {
      setLogs(unwrap<PageResult<AuditLog>>(await apiClient.get(`/api/admin/audit-logs?${queryString}`)));
    } catch {
      setError(localize("Không thể tải nhật ký hệ thống.", "Unable to load audit logs."));
    } finally { setLoading(false); }
  }, [isAdmin, localize, queryString]);

  const loadSummary = useCallback(async () => {
    if (!isAdmin) return;
    try {
      setSummary(unwrap<MonitoringSummary>(
        await apiClient.get("/api/admin/monitoring/summary"),
      ));
    } catch {
      setSummary(null);
    }
  }, [isAdmin]);

  useEffect(() => { void loadLogs(); }, [loadLogs]);
  useEffect(() => { void loadSummary(); }, [loadSummary]);
  useEffect(() => {
    if (!isAdmin) return;
    const timer = window.setTimeout(async () => {
      try {
        const query = filters.actor.trim() ? `?query=${encodeURIComponent(filters.actor.trim())}` : "";
        setActors(unwrap<string[]>(await apiClient.get(`/api/admin/audit-logs/actors${query}`)) || []);
      } catch { setActors([]); }
    }, 250);
    return () => window.clearTimeout(timer);
  }, [filters.actor, isAdmin]);

  const changeFilter = (key: keyof typeof filters, value: string) => {
    setPage(0); setFilters((current) => ({ ...current, [key]: value }));
  };

  if (role && !isAdmin) return <main className="p-6"><div className="rounded-xl border border-amber-200 bg-amber-50 p-6 text-amber-900"><h1 className="text-xl font-bold">{localize("Khu vực chỉ dành cho ADMIN", "ADMIN-only area")}</h1><p className="mt-2 text-sm">{localize("STAFF vẫn thao tác nghiệp vụ bình thường nhưng không được đọc audit trail.", "STAFF can perform normal operations but cannot read the audit trail.")}</p></div></main>;

  const changeKeys = selected ? Array.from(new Set([...Object.keys(selected.oldValue || {}), ...Object.keys(selected.newValue || {})])) : [];
  return (
    <main className="space-y-5 p-4 sm:p-6">
      <header className="rounded-xl bg-[#0F2A43] px-6 py-5 text-white shadow-sm">
        <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D7B66D]">{localize("Giám sát · Chỉ ADMIN", "Monitoring · ADMIN only")}</p>
        <h1 className="mt-2 font-serif text-3xl font-bold">{localize("Nhật ký hoạt động hệ thống", "System activity log")}</h1>
        <p className="mt-2 max-w-3xl text-sm text-white/75">{localize("Theo dõi ai đã thao tác, dữ liệu nào thay đổi và các hành động tài chính có rủi ro cao.", "Track who acted, what changed, and high-risk financial operations.")}</p>
      </header>

      {summary && <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4" aria-label={localize("Tình trạng vận hành", "Operational health")}>
        {[
          [localize("Webhook hôm nay", "Webhooks today"), summary.durableSePayEventsToday, "text-[#0F2A43]"],
          [localize("Chờ đối soát", "Review required"), summary.paymentEventsReviewRequired, summary.paymentEventsReviewRequired ? "text-amber-700" : "text-emerald-700"],
          [localize("RoomHold quá hạn", "Overdue holds"), summary.overdueActiveRoomHolds, summary.overdueActiveRoomHolds ? "text-rose-700" : "text-emerald-700"],
          [localize(`Refund quá ${summary.refundPendingThresholdMinutes} phút`, `Refunds over ${summary.refundPendingThresholdMinutes} min`), summary.stalePendingRefunds, summary.stalePendingRefunds ? "text-rose-700" : "text-emerald-700"],
          [localize(`Checkout chờ quá ${summary.checkoutPendingThresholdMinutes} phút`, `Checkout pending over ${summary.checkoutPendingThresholdMinutes} min`), summary.stalePendingCheckoutExceptionRequests, summary.stalePendingCheckoutExceptionRequests ? "text-rose-700" : "text-emerald-700"],
          [localize(`Webhook auth lỗi / ${summary.webhookAuthenticationFailureWindowMinutes} phút`, `Webhook auth failures / ${summary.webhookAuthenticationFailureWindowMinutes} min`), summary.webhookAuthenticationFailuresInWindow, summary.webhookAuthenticationFailuresInWindow >= summary.webhookAuthenticationFailureThresholdCount ? "text-rose-700" : "text-emerald-700"],
          [localize(`Email chờ quá ${summary.emailOutboxStaleThresholdMinutes} phút`, `Alerts pending over ${summary.emailOutboxStaleThresholdMinutes} min`), summary.staleEmailAlerts, summary.staleEmailAlerts ? "text-rose-700" : "text-emerald-700"],
          [localize("Email cảnh báo lỗi", "Failed alerts"), summary.failedEmailAlerts, summary.failedEmailAlerts ? "text-rose-700" : "text-emerald-700"],
        ].map(([label, value, tone]) => <article key={String(label)} className="rounded-xl border border-[#0F2A43]/10 bg-white p-4 shadow-sm"><p className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{label}</p><p className={`mt-2 text-2xl font-black ${tone}`}>{Number(value).toLocaleString("vi-VN")}</p></article>)}
      </section>}

      {summary && summary.sePayReconciliationStatus !== "HEALTHY" && <div className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm font-semibold text-amber-900" role="status">{localize("Đối soát SePay cần kiểm tra", "SePay reconciliation needs attention")}: {summary.sePayReconciliationStatus}{summary.sePayReconciliationLastRunAtUtc ? ` · ${formatTime(summary.sePayReconciliationLastRunAtUtc)}` : ""}.</div>}

      <section className="rounded-xl border border-[#0F2A43]/10 bg-white p-4 shadow-sm" aria-label={localize("Bộ lọc nhật ký", "Audit filters")}>
        <div className="grid gap-3 md:grid-cols-3 xl:grid-cols-5">
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Đối tượng", "Entity")}<select value={filters.targetType} onChange={(event) => changeFilter("targetType", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option>{["RESERVATION", "PAYMENT_TRANSACTION", "PAYMENT_PROVIDER_EVENT", "PAYMENT_REFUND", "ROOM_HOLD", "ROOM", "ROOM_TYPE", "FACILITY", "GALLERY", "USER", "SEPAY_WEBHOOK", "CHECKOUT_RECONCILIATION_REQUEST"].map((item) => <option key={item}>{item}</option>)}</select></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Mã đối tượng", "Entity ID")}<input value={filters.targetId} onChange={(event) => changeFilter("targetId", event.target.value)} maxLength={191} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 font-mono text-sm text-[#0F2A43]" placeholder={localize("VD: reservation/refund ID", "E.g. reservation/refund ID")} /></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Hành động", "Action")}<select value={filters.action} onChange={(event) => changeFilter("action", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option>{Object.keys(ACTION_LABELS).map((item) => <option key={item} value={item}>{ACTION_LABELS[item]}</option>)}</select></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Nhóm hành động", "Category")}<select value={filters.category} onChange={(event) => changeFilter("category", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option>{["RESERVATION", "PAYMENT", "REFUND", "ROOM_HOLD", "CHECKOUT", "SECURITY", "SYSTEM", "BUSINESS"].map((item) => <option key={item}>{item}</option>)}</select></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Mức rủi ro", "Risk")}<select value={filters.riskLevel} onChange={(event) => changeFilter("riskLevel", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option>{["NORMAL", "MEDIUM", "HIGH", "CRITICAL"].map((item) => <option key={item}>{item}</option>)}</select></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Người thao tác", "Actor")}<input list="audit-actors" value={filters.actor} onChange={(event) => changeFilter("actor", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 text-sm text-[#0F2A43]" placeholder={localize("Tên nhân viên", "Operator name")} /><datalist id="audit-actors">{actors.map((actor) => <option key={actor} value={actor} />)}</datalist></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Vai trò", "Actor role")}<select value={filters.actorRole} onChange={(event) => changeFilter("actorRole", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm text-[#0F2A43]"><option value="">{localize("Tất cả", "All")}</option>{["ADMIN", "STAFF", "CUSTOMER", "SYSTEM"].map((item) => <option key={item}>{item}</option>)}</select></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Từ thời điểm", "From")}<input type="datetime-local" value={filters.from} onChange={(event) => changeFilter("from", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 text-sm text-[#0F2A43]" /></label>
          <label className="grid gap-1 text-xs font-bold text-[#66727C]">{localize("Đến thời điểm", "To")}<input type="datetime-local" value={filters.to} onChange={(event) => changeFilter("to", event.target.value)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-3 text-sm text-[#0F2A43]" /></label>
        </div>
      </section>

      <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white shadow-sm">
        <div className="flex items-center justify-between border-b border-[#0F2A43]/10 px-5 py-4"><div><h2 className="font-bold text-[#0F2A43]">{localize("Hoạt động mới nhất", "Latest activity")}</h2><p className="text-xs text-[#66727C]">{logs ? `${logs.totalElements.toLocaleString("vi-VN")} log` : "—"}</p></div><button type="button" onClick={() => { void loadLogs(); void loadSummary(); }} disabled={loading} className="min-h-10 rounded-lg border border-[#0F2A43]/20 px-4 text-xs font-bold text-[#0F2A43] hover:bg-[#F1F0EA] disabled:opacity-50">{localize("Tải lại", "Refresh")}</button></div>
        {error && <p className="m-4 rounded-lg bg-rose-50 p-4 text-sm font-semibold text-rose-700">{error}</p>}
        {loading && !logs ? <div className="space-y-3 p-5">{[1, 2, 3, 4].map((item) => <div key={item} className="h-16 animate-pulse rounded-lg bg-[#F1F0EA]" />)}</div> : <div className="overflow-x-auto"><table className="min-w-full text-left text-sm"><thead className="bg-[#F1F0EA] text-[10px] uppercase tracking-wider text-[#66727C]"><tr><th className="px-4 py-3">{localize("Thời gian", "Time")}</th><th className="px-4 py-3">{localize("Người thao tác", "Actor")}</th><th className="px-4 py-3">{localize("Hành động", "Action")}</th><th className="px-4 py-3">{localize("Đối tượng", "Entity")}</th><th className="px-4 py-3">{localize("Rủi ro", "Risk")}</th><th className="px-4 py-3 text-right">{localize("Chi tiết", "Details")}</th></tr></thead><tbody className="divide-y divide-[#0F2A43]/8">{(logs?.content || []).map((log) => <tr key={log.id} className="hover:bg-[#F1F0EA]/60"><td className="whitespace-nowrap px-4 py-3 font-mono text-xs text-[#66727C]">{formatTime(log.occurredAtUtc)}</td><td className="px-4 py-3"><span className="block font-bold text-[#0F2A43]">{log.actorName || "Hệ thống"}</span><span className="text-[10px] font-bold uppercase text-[#80632F]">{log.actorRole}</span></td><td className="max-w-sm px-4 py-3"><span className="block font-semibold text-[#0F2A43]">{ACTION_LABELS[log.action] || log.action}</span><span className="line-clamp-2 text-xs text-[#66727C]">{log.details || "—"}</span></td><td className="px-4 py-3 font-mono text-xs text-[#66727C]">{log.reservationCode || `${log.targetType || "—"} #${log.targetId || "—"}`}</td><td className="px-4 py-3"><span className={`inline-flex rounded-full border px-2.5 py-1 text-[10px] font-black ${riskClass(log.riskLevel)}`}>{log.riskLevel}</span></td><td className="px-4 py-3 text-right"><button type="button" onClick={() => setSelected(log)} className="min-h-9 rounded-lg border border-[#0F2A43]/20 px-3 text-xs font-bold text-[#0F2A43] hover:border-[#B8944F] hover:bg-[#F1F0EA]">{localize("Xem", "View")}</button></td></tr>)}</tbody></table>{!logs?.content?.length && !loading && <p className="px-6 py-14 text-center text-sm text-[#66727C]">{localize("Không có log phù hợp bộ lọc.", "No logs match the filters.")}</p>}</div>}
        {logs && logs.totalPages > 1 && <div className="flex items-center justify-between border-t border-[#0F2A43]/10 px-5 py-4"><span className="text-xs font-semibold text-[#66727C]">{localize("Trang", "Page")} {logs.number + 1}/{logs.totalPages}</span><div className="flex gap-2"><button type="button" disabled={logs.first || loading} onClick={() => setPage((value) => Math.max(0, value - 1))} className="min-h-9 rounded-lg border px-3 text-xs font-bold disabled:opacity-40">{localize("Trước", "Previous")}</button><button type="button" disabled={logs.last || loading} onClick={() => setPage((value) => value + 1)} className="min-h-9 rounded-lg border px-3 text-xs font-bold disabled:opacity-40">{localize("Sau", "Next")}</button></div></div>}
      </section>

      <ViewportModal open={Boolean(selected)} onClose={() => setSelected(null)} labelledBy="audit-detail-title" panelClassName="max-w-3xl">
        {selected && <section className="min-h-0 overflow-y-auto p-5 sm:p-6"><div className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 pb-4"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{selected.category || "AUDIT"}</p><h2 id="audit-detail-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{ACTION_LABELS[selected.action] || selected.action}</h2><p className="mt-1 text-sm text-[#66727C]">{selected.actorName} · {selected.actorRole} · {formatTime(selected.occurredAtUtc)}</p></div><button type="button" onClick={() => setSelected(null)} aria-label={localize("Đóng", "Close")} className="flex h-10 w-10 items-center justify-center rounded-full border text-lg text-[#66727C] hover:bg-[#F1F0EA]">×</button></div><div className="mt-5 grid gap-4"><div className="rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] p-4"><p className="text-xs font-bold uppercase text-[#80632F]">{localize("Nội dung", "Summary")}</p><p className="mt-2 text-sm leading-6 text-[#0F2A43]">{selected.details || "—"}</p></div>{changeKeys.length > 0 && <div className="overflow-hidden rounded-xl border border-[#0F2A43]/10"><div className="bg-[#0F2A43] px-4 py-3 text-xs font-bold uppercase tracking-wider text-white">{localize("Dữ liệu thay đổi", "Changed fields")}</div><div className="divide-y divide-[#0F2A43]/10">{changeKeys.map((key) => <div key={key} className="grid gap-1 px-4 py-3 sm:grid-cols-[10rem_1fr]"><span className="text-xs font-bold text-[#66727C]">{FIELD_LABELS[key] || key}</span><span className="text-sm text-[#0F2A43]">{formatValue(key, selected.oldValue?.[key])} <span className="px-2 text-[#B8944F]">→</span> {formatValue(key, selected.newValue?.[key])}</span></div>)}</div></div>}{selected.detail && Object.keys(selected.detail).length > 0 && <div className="rounded-xl border border-[#0F2A43]/10 p-4"><p className="mb-3 text-xs font-bold uppercase text-[#80632F]">{localize("Thông tin nghiệp vụ", "Business context")}</p><dl className="grid gap-3 sm:grid-cols-2">{Object.entries(selected.detail).map(([key, value]) => <div key={key}><dt className="text-[10px] font-bold uppercase text-[#66727C]">{FIELD_LABELS[key] || key}</dt><dd className="mt-1 break-words text-sm font-semibold text-[#0F2A43]">{formatValue(key, value)}</dd></div>)}</dl></div>}{selected.correlationId && <p className="font-mono text-[11px] text-[#66727C]">Correlation: {selected.correlationId}</p>}</div></section>}
      </ViewportModal>
    </main>
  );
}
