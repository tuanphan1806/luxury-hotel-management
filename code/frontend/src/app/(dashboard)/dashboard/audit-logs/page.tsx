"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiClient } from "@/lib/api";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import {
  DashboardFilterPanel,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

type AuditRisk = "NORMAL" | "MEDIUM" | "HIGH" | "CRITICAL";
type AuditScope = "OPERATION" | "MANAGEMENT";

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
  scope?: AuditScope;
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

const ACTION_LABELS: Record<string, string> = {
  CONFIRM: "Xác nhận đặt phòng",
  CHECK_IN: "Làm thủ tục nhận phòng",
  CHECK_OUT: "Hoàn tất trả phòng",
  CANCEL: "Hủy đặt phòng",
  MARK_NO_SHOW: "Ghi nhận khách không đến",
  UPDATE_CHECKOUT_FEE: "Điều chỉnh phụ phí checkout",
  REFUND: "Xử lý hoàn tiền",
  PAYMENT_RECEIVED: "Ghi nhận tiền vào",
  PAYMENT_SESSION_CREATED: "Tạo phiên thanh toán",
  PAYMENT_ABANDONED: "Đóng phiên thanh toán bị bỏ dở",
  RECOVERED_ON_TIME_PAYMENT: "Khôi phục giao dịch đúng hạn",
  PROVIDER_EVENT_MATCHED: "Ghép giao dịch SePay",
  PROVIDER_EVENT_IGNORED: "Bỏ qua giao dịch chưa xác định",
  PROVIDER_EVENT_REFUND_CREATED: "Tạo nghĩa vụ hoàn từ giao dịch",
  PROVIDER_EVENT_REVIEW_REQUIRED: "Chuyển event SePay sang đối soát",
  WEBHOOK_AUTHENTICATION_REJECTED: "Từ chối webhook sai xác thực",
  ROOM_HOLD_RELEASED_MANUALLY: "Nhả giữ phòng thủ công",
  ROOM_HOLD_AUTO_EXPIRED: "RoomHold tự hết hạn",
  RESERVATION_AUTO_CANCELLED: "Hệ thống tự hủy reservation",
  SESSION_EXPIRED: "Phiên giữ chỗ hết hạn",
  PAYMENT_MARKED_PAID_MANUALLY: "ADMIN khôi phục payment thủ công",
  PRICE_OVERRIDDEN: "Áp dụng giá walk-in khác giá hệ thống",
  CHECKOUT_RECONCILIATION_REQUESTED: "Gửi yêu cầu xử lý lệch checkout",
  CHECKOUT_RECONCILIATION_REJECTED: "Từ chối yêu cầu lệch checkout",
  CHECKOUT_RECONCILIATION_RESOLVED_AUTOMATICALLY: "Tự đóng ngoại lệ khi đối soát đã khớp",
  CHECKOUT_RECONCILIATION_PASSED: "Đối soát checkout đã khớp",
  CHECKOUT_RECONCILIATION_OVERRIDDEN: "ADMIN duyệt ngoại lệ đối soát",
  USER_CREATED: "ADMIN tạo tài khoản vận hành",
  USER_DEACTIVATED: "ADMIN vô hiệu hóa tài khoản",
  USER_ROLE_CHANGED: "Thay đổi quyền tài khoản",
  PASSWORD_RESET_BY_ADMIN: "ADMIN đặt lại mật khẩu",
  ROOM_CREATED: "Tạo phòng",
  ROOM_UPDATED: "Cập nhật phòng",
  ROOM_DELETED: "Xóa phòng",
  ROOM_TYPE_CREATED: "Tạo hạng phòng",
  ROOM_TYPE_UPDATED: "Cập nhật hạng phòng",
  ROOM_TYPE_DELETED: "Xóa hạng phòng",
  FACILITY_CREATED: "Tạo tiện nghi",
  FACILITY_UPDATED: "Cập nhật tiện nghi",
  FACILITY_DELETED: "Xóa tiện nghi",
  GALLERY_CREATED: "Tạo ảnh thư viện",
  GALLERY_UPDATED: "Cập nhật ảnh thư viện",
  GALLERY_DELETED: "Xóa ảnh thư viện",
};

const TARGET_LABELS: Record<string, string> = {
  RESERVATION: "Đơn đặt phòng",
  PAYMENT_TRANSACTION: "Thanh toán",
  PAYMENT_PROVIDER_EVENT: "Giao dịch SePay",
  PAYMENT_REFUND: "Hoàn tiền",
  ROOM_HOLD: "Giữ phòng",
  ROOM: "Phòng",
  ROOM_TYPE: "Hạng phòng",
  FACILITY: "Tiện nghi",
  GALLERY: "Thư viện ảnh",
  USER: "Tài khoản vận hành",
  SEPAY_WEBHOOK: "Webhook SePay",
  CHECKOUT_RECONCILIATION_REQUEST: "Yêu cầu đối soát checkout",
};

const CATEGORY_LABELS: Record<string, string> = {
  RESERVATION: "Đặt phòng",
  PAYMENT: "Thanh toán",
  REFUND: "Hoàn tiền",
  ROOM_HOLD: "Giữ phòng",
  CHECKOUT: "Checkout",
  BUSINESS: "Danh mục khách sạn",
  SECURITY: "Quản trị tài khoản",
  SYSTEM: "Tự động hệ thống",
};

const SCOPE_LABELS: Record<AuditScope, string> = {
  OPERATION: "Vận hành",
  MANAGEMENT: "Quản lý",
};

const CATEGORY_SCOPES: Record<string, AuditScope> = {
  RESERVATION: "OPERATION",
  PAYMENT: "OPERATION",
  REFUND: "OPERATION",
  ROOM_HOLD: "OPERATION",
  CHECKOUT: "OPERATION",
  SYSTEM: "OPERATION",
  BUSINESS: "MANAGEMENT",
  SECURITY: "MANAGEMENT",
};

const MANAGEMENT_ACTIONS = new Set([
  "USER_CREATED", "USER_DEACTIVATED", "USER_ROLE_CHANGED", "PASSWORD_RESET_BY_ADMIN",
  "ROOM_CREATED", "ROOM_UPDATED", "ROOM_DELETED",
  "ROOM_TYPE_CREATED", "ROOM_TYPE_UPDATED", "ROOM_TYPE_DELETED",
  "FACILITY_CREATED", "FACILITY_UPDATED", "FACILITY_DELETED",
  "GALLERY_CREATED", "GALLERY_UPDATED", "GALLERY_DELETED",
]);

const MANAGEMENT_TARGETS = new Set(["USER", "ROOM", "ROOM_TYPE", "FACILITY", "GALLERY"]);

function belongsToScope(value: string, scope: "" | AuditScope, managementValues: Set<string>) {
  if (!value || !scope) return true;
  return scope === "MANAGEMENT" ? managementValues.has(value) : !managementValues.has(value);
}

const RISK_LABELS: Record<AuditRisk, string> = {
  NORMAL: "Thông thường",
  MEDIUM: "Cần chú ý",
  HIGH: "Rủi ro cao",
  CRITICAL: "Khẩn cấp",
};

const FIELD_LABELS: Record<string, string> = {
  status: "Trạng thái",
  role: "Vai trò",
  eventStatus: "Trạng thái event",
  paymentStatus: "Trạng thái payment",
  reservationStatus: "Trạng thái reservation",
  amount: "Số tiền",
  unitPrice: "Đơn giá",
  subtotal: "Thành tiền",
  totalAmount: "Tổng tiền",
  requiredAmount: "Cần thu",
  acceptedAmount: "Đã thu",
  deltaAmount: "Chênh lệch",
  reasonCode: "Nhóm lý do",
  reason: "Lý do",
  note: "Ghi chú",
  quantity: "Số lượng",
  roomTypeName: "Hạng phòng",
  originalAmount: "Tiền gốc theo ledger",
  penaltyAmount: "Tiền phạt hủy",
  penaltyPercent: "Tỷ lệ phạt",
  refundAmount: "Số tiền hoàn",
  policyApplied: "Chính sách áp dụng",
  policyNote: "Căn cứ chính sách",
  sessionsInvalidated: "Đã thu hồi phiên đăng nhập",
  method: "Phương thức",
  transferType: "Chiều giao dịch",
  providerEventId: "Mã event SePay",
  providerReference: "Mã tham chiếu nhà cung cấp",
  bankReferenceCode: "Mã tham chiếu ngân hàng",
  paymentCode: "Mã thanh toán",
  refundCode: "Mã hoàn tiền",
  merchantAccountId: "Tài khoản nhận tiền",
  accountNumberMasked: "Số tài khoản đã che",
  transactionDate: "Thời gian giao dịch",
  expectedAmount: "Số tiền dự kiến",
  receivedAmount: "Số tiền thực nhận",
};

const EMPTY_FILTERS = {
  scope: "",
  targetType: "",
  targetId: "",
  actor: "",
  actorRole: "",
  action: "",
  category: "",
  riskLevel: "",
  from: "",
  to: "",
};

function unwrap<T>(response: { data?: { data?: T } | T }): T {
  const payload = response.data;
  return payload && typeof payload === "object" && "data" in payload
    ? (payload as { data: T }).data
    : payload as T;
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

function entityLabel(log: AuditLog) {
  if (log.reservationCode) return `Đơn ${log.reservationCode}`;
  return `${TARGET_LABELS[log.targetType || ""] || log.targetType || "Đối tượng"} #${log.targetId || "—"}`;
}

export default function AuditLogsPage() {
  const { localeTag, localize } = useLanguage();
  const { role, isAdmin } = useDashboardRole();
  const [logs, setLogs] = useState<PageResult<AuditLog> | null>(null);
  const [selected, setSelected] = useState<AuditLog | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [page, setPage] = useState(0);
  const [actors, setActors] = useState<string[]>([]);
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [filters, setFilters] = useState(EMPTY_FILTERS);

  const formatTime = useCallback((value?: string) => {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString(localeTag, {
      day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit",
    });
  }, [localeTag]);

  const queryString = useMemo(() => {
    const params = new URLSearchParams({ page: String(page), size: "25" });
    Object.entries(filters).forEach(([key, value]) => {
      if (!value) return;
      if (key === "from" || key === "to") {
        const date = new Date(value);
        if (!Number.isNaN(date.getTime())) params.set(key, date.toISOString());
      } else {
        params.set(key, value);
      }
    });
    return params.toString();
  }, [filters, page]);

  const loadLogs = useCallback(async () => {
    if (!isAdmin) return;
    setLoading(true);
    setError("");
    try {
      setLogs(unwrap<PageResult<AuditLog>>(await apiClient.get(`/api/admin/audit-logs?${queryString}`)));
    } catch {
      setError(localize("Không thể tải nhật ký vận hành.", "Unable to load the operations audit log."));
    } finally {
      setLoading(false);
    }
  }, [isAdmin, localize, queryString]);

  useEffect(() => { void loadLogs(); }, [loadLogs]);
  useEffect(() => {
    if (!isAdmin) return;
    apiClient.get("/api/admin/audit-logs/actors")
      .then((response) => setActors(unwrap<string[]>(response) || []))
      .catch(() => setActors([]));
  }, [isAdmin]);

  const changeFilter = (key: keyof typeof filters, value: string) => {
    setPage(0);
    setFilters((current) => ({ ...current, [key]: value }));
  };

  const setScopeFilter = (scope: "" | AuditScope) => {
    setPage(0);
    setFilters((current) => {
      const categoryMatchesScope = !current.category
        || !scope
        || CATEGORY_SCOPES[current.category] === scope;
      return {
        ...current,
        scope,
        category: categoryMatchesScope ? current.category : "",
        action: belongsToScope(current.action, scope, MANAGEMENT_ACTIONS) ? current.action : "",
        targetType: belongsToScope(current.targetType, scope, MANAGEMENT_TARGETS) ? current.targetType : "",
      };
    });
  };

  const toggleScope = (scope: AuditScope) => {
    setScopeFilter(filters.scope === scope ? "" : scope);
  };

  const togglePaymentFilter = () => {
    setPage(0);
    setFilters((current) => ({
      ...current,
      scope: "OPERATION",
      category: current.scope === "OPERATION" && current.category === "PAYMENT" ? "" : "PAYMENT",
      action: belongsToScope(current.action, "OPERATION", MANAGEMENT_ACTIONS) ? current.action : "",
      targetType: belongsToScope(current.targetType, "OPERATION", MANAGEMENT_TARGETS) ? current.targetType : "",
    }));
  };

  const resetFilters = () => {
    setPage(0);
    setFilters(EMPTY_FILTERS);
    setShowAdvanced(false);
  };

  if (role && !isAdmin) return (
    <main className="p-6"><div className="rounded-xl border border-amber-200 bg-amber-50 p-6 text-amber-900"><h1 className="text-xl font-bold">{localize("Khu vực chỉ dành cho ADMIN", "ADMIN-only area")}</h1><p className="mt-2 text-sm">{localize("STAFF vẫn thao tác nghiệp vụ bình thường nhưng không được đọc audit trail.", "STAFF can perform normal operations but cannot read the audit trail.")}</p></div></main>
  );

  const activeFilterCount = Object.values(filters).filter(Boolean).length;
  const visibleCategories = Object.entries(CATEGORY_LABELS).filter(([category]) =>
    !filters.scope || CATEGORY_SCOPES[category] === filters.scope);
  const visibleActions = Object.entries(ACTION_LABELS).filter(([action]) =>
    belongsToScope(action, filters.scope as "" | AuditScope, MANAGEMENT_ACTIONS));
  const visibleTargets = Object.entries(TARGET_LABELS).filter(([target]) =>
    belongsToScope(target, filters.scope as "" | AuditScope, MANAGEMENT_TARGETS));
  const changeKeys = selected ? Array.from(new Set([
    ...Object.keys(selected.oldValue || {}),
    ...Object.keys(selected.newValue || {}),
  ])) : [];

  return (
    <main className="space-y-5 p-4 sm:p-6 lg:p-8">
      <header className="rounded-xl bg-[#0F2A43] px-6 py-6 text-white shadow-sm">
        <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D7B66D]">{localize("Vận hành · Chỉ ADMIN", "Operations · ADMIN only")}</p>
        <h1 className="mt-2 font-serif text-3xl font-bold">{localize("Nhật ký vận hành & quản trị", "Operations & management log")}</h1>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-white/80">{localize("Tập trung vào các thao tác làm thay đổi đơn, tiền, tồn phòng, danh mục và quyền quản trị. Đăng ký, đăng nhập, đăng xuất và đổi mật khẩu cá nhân không hiển thị tại đây.", "Focused on mutations to reservations, money, inventory, catalogues and administrative access. Routine sign-up, sign-in, sign-out and self-service password events are excluded.")}</p>
      </header>

      <DashboardFilterPanel
        title={localize("Lọc hoạt động cần kiểm tra", "Filter operational activity")}
        description={localize("Chọn từ danh sách có sẵn; chỉ mở bộ lọc nâng cao khi cần tra cứu một đối tượng cụ thể.", "Use prepared options and open advanced filters only for a specific investigation.")}
        resultCount={logs?.totalElements || 0}
        resultLabel={localize("hoạt động phù hợp", "matching activities")}
        resultNote={logs ? `${localize("Trang", "Page")} ${logs.number + 1}/${Math.max(1, logs.totalPages)}` : undefined}
        hasActiveFilters={activeFilterCount > 0}
        activeFilterCount={activeFilterCount}
        activeFilterLabel={localize("điều kiện đang dùng", "active filters")}
        onReset={resetFilters}
        resetLabel={localize("Xóa toàn bộ bộ lọc", "Clear all filters")}
        actions={<>
          <FilterQuickButton active={filters.scope === "OPERATION" && !filters.category} onClick={() => toggleScope("OPERATION")}>{localize("Vận hành", "Operations")}</FilterQuickButton>
          <FilterQuickButton active={filters.scope === "MANAGEMENT" && !filters.category} onClick={() => toggleScope("MANAGEMENT")}>{localize("Quản lý", "Management")}</FilterQuickButton>
          <FilterQuickButton active={filters.scope === "OPERATION" && filters.category === "PAYMENT"} onClick={togglePaymentFilter}>{localize("Thanh toán", "Payments")}</FilterQuickButton>
          <FilterQuickButton active={filters.riskLevel === "HIGH"} onClick={() => changeFilter("riskLevel", filters.riskLevel === "HIGH" ? "" : "HIGH")}>{localize("Rủi ro cao", "High risk")}</FilterQuickButton>
        </>}
      >
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-5">
          <DashboardSelectField id="audit-scope" label={localize("Phạm vi", "Scope")} value={filters.scope} onChange={(event) => setScopeFilter(event.target.value as "" | AuditScope)}>
            <option value="">{localize("Tất cả vận hành & quản lý", "All operations & management")}</option>
            <option value="OPERATION">{localize(SCOPE_LABELS.OPERATION, "Operations")}</option>
            <option value="MANAGEMENT">{localize(SCOPE_LABELS.MANAGEMENT, "Management")}</option>
          </DashboardSelectField>
          <DashboardSelectField id="audit-target" label={localize("Đối tượng", "Entity")} value={filters.targetType} onChange={(event) => changeFilter("targetType", event.target.value)}>
            <option value="">{localize("Tất cả đối tượng", "All entities")}</option>
            {visibleTargets.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </DashboardSelectField>
          <DashboardSelectField id="audit-action" label={localize("Hành động", "Action")} value={filters.action} onChange={(event) => changeFilter("action", event.target.value)}>
            <option value="">{localize("Tất cả hành động", "All actions")}</option>
            {visibleActions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </DashboardSelectField>
          <DashboardSelectField id="audit-actor" label={localize("Người thao tác", "Actor")} value={filters.actor} onChange={(event) => changeFilter("actor", event.target.value)}>
            <option value="">{localize("Tất cả nhân sự và hệ thống", "All operators and system")}</option>
            {actors.map((actor) => <option key={actor} value={actor}>{actor}</option>)}
          </DashboardSelectField>
          <DashboardSelectField id="audit-category" label={localize("Nhóm nghiệp vụ", "Operation group")} value={filters.category} onChange={(event) => changeFilter("category", event.target.value)}>
            <option value="">{localize("Tất cả nhóm", "All groups")}</option>
            {visibleCategories.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </DashboardSelectField>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[#0F2A43]/10 pt-3">
          <p className="text-xs text-[#66727C]">{localize("Mặc định sắp xếp hoạt động mới nhất trước.", "Newest activity appears first by default.")}</p>
          <button type="button" onClick={() => setShowAdvanced((value) => !value)} aria-expanded={showAdvanced} className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F]">
            {showAdvanced ? localize("Ẩn bộ lọc nâng cao", "Hide advanced filters") : localize("Bộ lọc nâng cao", "Advanced filters")}
            <span aria-hidden="true">{showAdvanced ? "−" : "+"}</span>
          </button>
        </div>

        {showAdvanced && <div className="grid gap-3 rounded-xl bg-[#F1F0EA] p-4 md:grid-cols-2 xl:grid-cols-5">
          <label className="grid gap-2 text-xs font-bold text-[#66727C]">{localize("Mã đối tượng", "Entity ID")}<input value={filters.targetId} onChange={(event) => changeFilter("targetId", event.target.value)} maxLength={191} className="ops-control min-h-11 rounded-lg border px-3 font-mono text-sm text-[#0F2A43]" placeholder={localize("Mã đơn/refund/room...", "Reservation/refund/room ID...")} /></label>
          <DashboardSelectField id="audit-role" label={localize("Vai trò thao tác", "Actor role")} value={filters.actorRole} onChange={(event) => changeFilter("actorRole", event.target.value)}>
            <option value="">{localize("Tất cả vai trò", "All roles")}</option>{["ADMIN", "STAFF", "SYSTEM"].map((item) => <option key={item}>{item}</option>)}
          </DashboardSelectField>
          <DashboardSelectField id="audit-risk" label={localize("Mức độ", "Risk level")} value={filters.riskLevel} onChange={(event) => changeFilter("riskLevel", event.target.value)}>
            <option value="">{localize("Tất cả mức độ", "All risk levels")}</option>{Object.entries(RISK_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </DashboardSelectField>
          <label className="grid gap-2 text-xs font-bold text-[#66727C]">{localize("Từ thời điểm", "From")}<input type="datetime-local" value={filters.from} onChange={(event) => changeFilter("from", event.target.value)} className="ops-control min-h-11 rounded-lg border px-3 text-sm text-[#0F2A43]" /></label>
          <label className="grid gap-2 text-xs font-bold text-[#66727C]">{localize("Đến thời điểm", "To")}<input type="datetime-local" value={filters.to} onChange={(event) => changeFilter("to", event.target.value)} className="ops-control min-h-11 rounded-lg border px-3 text-sm text-[#0F2A43]" /></label>
        </div>}
      </DashboardFilterPanel>

      <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="audit-list-title">
        <div className="flex items-center justify-between gap-4 border-b border-[#0F2A43]/10 px-5 py-4"><div><h2 id="audit-list-title" className="font-bold text-[#0F2A43]">{localize("Dòng thời gian thao tác", "Operations timeline")}</h2><p className="mt-0.5 text-xs text-[#66727C]">{localize("Ai đã làm gì, trên đối tượng nào và vào thời điểm nào.", "Who did what, to which entity, and when.")}</p></div><button type="button" onClick={() => void loadLogs()} disabled={loading} className="min-h-10 rounded-lg border border-[#0F2A43]/20 px-4 text-xs font-bold text-[#0F2A43] hover:bg-[#F1F0EA] disabled:opacity-50">{loading ? localize("Đang tải...", "Loading...") : localize("Tải lại", "Refresh")}</button></div>
        {error && <p className="m-4 rounded-lg bg-rose-50 p-4 text-sm font-semibold text-rose-700">{error}</p>}
        {loading && !logs ? <div className="space-y-3 p-5">{[1, 2, 3, 4].map((item) => <div key={item} className="h-20 animate-pulse rounded-lg bg-[#F1F0EA]" />)}</div> : <>
          <div className="hidden overflow-x-auto lg:block"><table className="min-w-full text-left text-sm"><thead className="bg-[#F1F0EA] text-[10px] uppercase tracking-wider text-[#66727C]"><tr><th className="px-5 py-3">{localize("Hoạt động", "Activity")}</th><th className="px-4 py-3">{localize("Người thực hiện", "Actor")}</th><th className="px-4 py-3">{localize("Đối tượng", "Entity")}</th><th className="px-4 py-3">{localize("Thời gian", "Time")}</th><th className="px-4 py-3">{localize("Mức độ", "Risk")}</th><th className="px-5 py-3 text-right">{localize("Chi tiết", "Details")}</th></tr></thead><tbody className="divide-y divide-[#0F2A43]/8">{(logs?.content || []).map((log) => <tr key={log.id} className="align-top hover:bg-[#F1F0EA]/60"><td className="max-w-md px-5 py-4"><div className="flex gap-3"><span aria-hidden="true" className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-[#0F2A43] text-xs font-black text-[#D8C398]">{(CATEGORY_LABELS[log.category || ""] || "H").slice(0, 1)}</span><div><span className="block font-bold text-[#0F2A43]">{ACTION_LABELS[log.action] || log.action}</span><span className="mt-1 line-clamp-2 text-xs leading-5 text-[#66727C]">{log.details || localize("Không có ghi chú bổ sung", "No additional note")}</span></div></div></td><td className="px-4 py-4"><span className="block font-bold text-[#0F2A43]">{log.actorName || localize("Hệ thống", "System")}</span><span className="mt-1 text-[10px] font-bold uppercase tracking-wider text-[#80632F]">{log.actorRole}</span></td><td className="px-4 py-4"><span className="font-semibold text-[#27445F]">{entityLabel(log)}</span><span className="mt-1 block text-[10px] uppercase tracking-wider text-[#66727C]">{CATEGORY_LABELS[log.category || ""] || log.category || "—"}</span></td><td className="whitespace-nowrap px-4 py-4 text-xs tabular-nums text-[#66727C]">{formatTime(log.occurredAtUtc)}</td><td className="px-4 py-4"><span className={`inline-flex rounded-full border px-2.5 py-1 text-[10px] font-black ${riskClass(log.riskLevel)}`}>{RISK_LABELS[log.riskLevel]}</span></td><td className="px-5 py-4 text-right"><button type="button" onClick={() => setSelected(log)} className="min-h-9 rounded-lg border border-[#0F2A43]/20 px-3 text-xs font-bold text-[#0F2A43] hover:border-[#B8944F] hover:bg-[#F1F0EA]">{localize("Xem thay đổi", "View changes")}</button></td></tr>)}</tbody></table></div>
          <div className="divide-y divide-[#0F2A43]/10 lg:hidden">{(logs?.content || []).map((log) => <article key={log.id} className="p-4"><div className="flex items-start justify-between gap-3"><div><p className="font-bold text-[#0F2A43]">{ACTION_LABELS[log.action] || log.action}</p><p className="mt-1 text-xs text-[#66727C]">{entityLabel(log)}</p></div><span className={`shrink-0 rounded-full border px-2 py-1 text-[9px] font-black ${riskClass(log.riskLevel)}`}>{RISK_LABELS[log.riskLevel]}</span></div><p className="mt-3 line-clamp-2 text-xs leading-5 text-[#66727C]">{log.details || "—"}</p><div className="mt-3 flex items-end justify-between gap-3"><div><p className="text-xs font-bold text-[#27445F]">{log.actorName || localize("Hệ thống", "System")} · {log.actorRole}</p><p className="mt-1 text-[10px] tabular-nums text-[#66727C]">{formatTime(log.occurredAtUtc)}</p></div><button type="button" onClick={() => setSelected(log)} className="min-h-9 rounded-lg border px-3 text-xs font-bold">{localize("Chi tiết", "Details")}</button></div></article>)}</div>
          {!logs?.content?.length && !loading && <p className="px-6 py-14 text-center text-sm text-[#66727C]">{localize("Không có hoạt động vận hành phù hợp bộ lọc.", "No operational activity matches the filters.")}</p>}
        </>}
        {logs && logs.totalPages > 1 && <div className="flex items-center justify-between border-t border-[#0F2A43]/10 px-5 py-4"><span className="text-xs font-semibold text-[#66727C]">{localize("Trang", "Page")} {logs.number + 1}/{logs.totalPages}</span><div className="flex gap-2"><button type="button" disabled={logs.first || loading} onClick={() => setPage((value) => Math.max(0, value - 1))} className="min-h-9 rounded-lg border px-3 text-xs font-bold disabled:opacity-40">{localize("Trước", "Previous")}</button><button type="button" disabled={logs.last || loading} onClick={() => setPage((value) => value + 1)} className="min-h-9 rounded-lg border px-3 text-xs font-bold disabled:opacity-40">{localize("Sau", "Next")}</button></div></div>}
      </section>

      <ViewportModal open={Boolean(selected)} onClose={() => setSelected(null)} labelledBy="audit-detail-title" panelClassName="max-w-3xl">
        {selected && <section className="min-h-0 overflow-y-auto p-5 sm:p-6"><div className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 pb-4"><div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{CATEGORY_LABELS[selected.category || ""] || selected.category || "AUDIT"}</p><h2 id="audit-detail-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{ACTION_LABELS[selected.action] || selected.action}</h2><p className="mt-1 text-sm text-[#66727C]">{selected.actorName || localize("Hệ thống", "System")} · {selected.actorRole} · {formatTime(selected.occurredAtUtc)}</p><p className="mt-1 text-xs font-semibold text-[#27445F]">{entityLabel(selected)}</p></div><button type="button" onClick={() => setSelected(null)} aria-label={localize("Đóng", "Close")} className="flex h-10 w-10 items-center justify-center rounded-full border text-lg text-[#66727C] hover:bg-[#F1F0EA]">×</button></div><div className="mt-5 grid gap-4"><div className="rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] p-4"><p className="text-xs font-bold uppercase text-[#80632F]">{localize("Nội dung thao tác", "Activity summary")}</p><p className="mt-2 text-sm leading-6 text-[#0F2A43]">{selected.details || "—"}</p></div>{changeKeys.length > 0 && <div className="overflow-hidden rounded-xl border border-[#0F2A43]/10"><div className="bg-[#0F2A43] px-4 py-3 text-xs font-bold uppercase tracking-wider text-white">{localize("Trước và sau thay đổi", "Before and after")}</div><div className="divide-y divide-[#0F2A43]/10">{changeKeys.map((key) => <div key={key} className="grid gap-1 px-4 py-3 sm:grid-cols-[10rem_1fr]"><span className="text-xs font-bold text-[#66727C]">{FIELD_LABELS[key] || key}</span><span className="text-sm text-[#0F2A43]">{formatValue(key, selected.oldValue?.[key])} <span className="px-2 text-[#B8944F]">→</span> {formatValue(key, selected.newValue?.[key])}</span></div>)}</div></div>}{selected.detail && Object.keys(selected.detail).length > 0 && <div className="rounded-xl border border-[#0F2A43]/10 p-4"><p className="mb-3 text-xs font-bold uppercase text-[#80632F]">{localize("Thông tin nghiệp vụ", "Business context")}</p><dl className="grid gap-3 sm:grid-cols-2">{Object.entries(selected.detail).map(([key, value]) => <div key={key}><dt className="text-[10px] font-bold uppercase text-[#66727C]">{FIELD_LABELS[key] || key}</dt><dd className="mt-1 break-words text-sm font-semibold text-[#0F2A43]">{formatValue(key, value)}</dd></div>)}</dl></div>}{selected.correlationId && <p className="font-mono text-[11px] text-[#66727C]">Correlation: {selected.correlationId}</p>}</div></section>}
      </ViewportModal>
    </main>
  );
}
