"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import BookingAddOnSelector from "@/components/add-on-services/BookingAddOnSelector";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import {
  type AddOnSelection,
  type AddOnServiceItem,
  type ReservationServiceItem,
  calculateAddOnLineTotal,
  chargeableNights,
  getAddOnCatalog,
  pricingUnitLabel,
  serviceStatusLabel,
} from "@/lib/add-on-services";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";
import { resolveMediaSource } from "@/lib/media-url";

interface Props {
  reservationId: number;
  reservationCode?: string;
  reservationStatus: string;
  guestCount?: number;
  checkIn?: string;
  checkOut?: string;
  actualCheckIn?: string;
  initialServices?: ReservationServiceItem[];
  operator?: boolean;
  onChanged?: () => void | Promise<void>;
}

const statusTone = (status: ReservationServiceItem["status"]) => ({
  REQUESTED: "border-amber-200 bg-amber-50 text-amber-800",
  CONFIRMED: "border-sky-200 bg-sky-50 text-sky-800",
  FULFILLED: "border-emerald-200 bg-emerald-50 text-emerald-800",
  CANCELLED: "border-rose-200 bg-rose-50 text-rose-800",
}[status]);

export default function ReservationServicesPanel({
  reservationId,
  reservationCode,
  reservationStatus,
  guestCount = 1,
  checkIn,
  checkOut,
  actualCheckIn,
  initialServices = [],
  operator = false,
  onChanged,
}: Props) {
  const { localeTag, localize } = useLanguage();
  const [orders, setOrders] = useState<ReservationServiceItem[]>(initialServices);
  const [catalog, setCatalog] = useState<AddOnServiceItem[]>([]);
  const [selections, setSelections] = useState<Record<number, AddOnSelection>>({});
  const [batchDraftKey, setBatchDraftKey] = useState(
    () => `${Date.now()}-${Math.random().toString(36).slice(2)}`,
  );
  const [cancelTarget, setCancelTarget] = useState<number | null>(null);
  const [cancellationReason, setCancellationReason] = useState("");
  const [loading, setLoading] = useState(true);
  const [busyKey, setBusyKey] = useState("");
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  const canRequest = operator
    ? ["CONFIRMED", "CHECKED_IN"].includes(reservationStatus)
    : reservationStatus === "CHECKED_IN";

  const money = (value?: number) => Number(value || 0).toLocaleString(localeTag, {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  });

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [ordersResult, catalogResult] = await Promise.allSettled([
        apiClient.get(`/api/reservations/${reservationId}/services`),
        canRequest ? getAddOnCatalog("IN_STAY") : Promise.resolve([]),
      ]);
      if (ordersResult.status === "rejected") throw ordersResult.reason;
      const ordersResponse = ordersResult.value;
      const orderPayload = ordersResponse.data?.data ?? ordersResponse.data;
      setOrders(Array.isArray(orderPayload)
        ? orderPayload.map((item) => ({
            ...item,
            id: Number(item.id),
            reservationId: Number(item.reservationId),
            serviceId: Number(item.serviceId),
            unitPrice: Number(item.unitPrice || 0),
            totalPrice: Number(item.totalPrice || 0),
          }))
        : []);
      if (catalogResult.status === "fulfilled") {
        setCatalog(catalogResult.value);
      } else {
        setCatalog([]);
        setError(getApiErrorMessage(catalogResult.reason, localize("Đã tải lịch sử dịch vụ nhưng chưa thể tải danh mục để thêm mới.", "Service history loaded, but the catalog is temporarily unavailable.")));
      }
    } catch (loadError: unknown) {
      setError(getApiErrorMessage(loadError, localize("Không thể tải dịch vụ của đơn.", "Could not load reservation services.")));
    } finally {
      setLoading(false);
    }
  }, [canRequest, localize, reservationId]);

  useEffect(() => {
    void load();
  }, [load]);

  const selectedCount = Object.keys(selections).length;
  const estimatedPackageCycles = chargeableNights(actualCheckIn || checkIn, checkOut);
  const selectedEstimate = useMemo(() => Object.entries(selections)
    .reduce((sum, [serviceId, selection]) => {
      const service = catalog.find((item) => item.id === Number(serviceId));
      return service
        ? sum + calculateAddOnLineTotal(
            service, selection, guestCount, estimatedPackageCycles,
          )
        : sum;
    }, 0), [catalog, estimatedPackageCycles, guestCount, selections]);
  const committedTotal = useMemo(() => orders
    .filter((item) => item.status === "CONFIRMED" || item.status === "FULFILLED")
    .reduce((sum, item) => sum + item.totalPrice, 0), [orders]);

  const requestServices = async () => {
    const selectedRequests = Object.entries(selections)
      .map(([serviceId, selection]) => ({
        serviceId: Number(serviceId),
        quantity: selection.quantity,
        notes: selection.notes.trim() || undefined,
      }))
      .sort((left, right) => left.serviceId - right.serviceId);
    if (selectedRequests.length === 0) {
      setError(localize("Vui lòng chọn ít nhất một dịch vụ.", "Select at least one service."));
      return;
    }
    const scope = `reservation-service:${reservationId}:batch:${batchDraftKey}`;
    setBusyKey(scope);
    setError("");
    setSuccess("");
    try {
      await apiClient.post(`/api/reservations/${reservationId}/services/batch`, {
        services: selectedRequests,
      }, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) },
      });
      clearIdempotencyKey(scope);
      setSelections({});
      setBatchDraftKey(`${Date.now()}-${Math.random().toString(36).slice(2)}`);
      setSuccess(operator
        ? localize(
            `Đã tạo ${selectedRequests.length} yêu cầu; hãy xác nhận từng dịch vụ trước khi khoản phí được cộng.`,
            `${selectedRequests.length} requests created; confirm each service before charges are added.`,
          )
        : localize(
            `Đã gửi ${selectedRequests.length} yêu cầu tới lễ tân.`,
            `${selectedRequests.length} requests were sent to the front desk.`,
          ));
      await load();
      await onChanged?.();
    } catch (requestError: unknown) {
      setError(getApiErrorMessage(requestError, localize("Không thể gửi các yêu cầu dịch vụ.", "Could not request the services.")));
    } finally {
      setBusyKey("");
    }
  };

  const updateStatus = async (
    order: ReservationServiceItem,
    status: "CONFIRMED" | "FULFILLED" | "CANCELLED",
  ) => {
    const reason = status === "CANCELLED" ? cancellationReason.trim() : "";
    if (status === "CANCELLED" && !reason) {
      setError(localize("Phải nhập lý do hủy dịch vụ.", "A cancellation reason is required."));
      return;
    }
    const scope = `reservation-service:${reservationId}:${order.id}:${status}`;
    setBusyKey(scope);
    setError("");
    setSuccess("");
    try {
      await apiClient.patch(
        `/api/reservations/${reservationId}/services/${order.id}/status`,
        { status, cancellationReason: reason || undefined },
        { headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) } },
      );
      clearIdempotencyKey(scope);
      setCancelTarget(null);
      setCancellationReason("");
      setSuccess(status === "CONFIRMED"
        ? localize("Đã xác nhận và cộng phí vào đơn.", "Confirmed and added to the reservation total.")
        : status === "FULFILLED"
          ? localize("Đã ghi nhận phục vụ hoàn tất.", "Service marked as fulfilled.")
          : localize("Đã hủy yêu cầu dịch vụ.", "Service request cancelled."));
      await load();
      await onChanged?.();
    } catch (statusError: unknown) {
      setError(getApiErrorMessage(statusError, localize("Không thể cập nhật trạng thái dịch vụ.", "Could not update the service status.")));
    } finally {
      setBusyKey("");
    }
  };

  return (
    <section className="flex flex-col gap-4 rounded-xl border border-[#0F2A43]/12 bg-white p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#0F2A43]/10 pb-3">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Dịch vụ thêm", "Add-on services")}</p>
          <h3 className="mt-1 font-serif text-lg font-bold text-[#0F2A43]">{reservationCode || `#${reservationId}`}</h3>
        </div>
        <div className="text-right"><p className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Phí đã xác nhận", "Committed charges")}</p><p className="mt-1 font-black tabular-nums text-[#80632F]">{money(committedTotal)}</p></div>
      </div>

      {error && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-semibold text-rose-700">{error}</p>}
      {success && <p role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm font-semibold text-emerald-800">{success}</p>}

      <div className="space-y-3">
        <div>
          <p className="text-sm font-bold text-[#0F2A43]">{localize("Dịch vụ của đơn", "Reservation services")}</p>
          <p className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Theo dõi yêu cầu, phí đã xác nhận và trạng thái phục vụ tại đây.", "Track requests, confirmed charges, and fulfilment here.")}</p>
        </div>
        {loading ? (
          <div className="space-y-2" role="status">{[1, 2].map((item) => <div key={item} className="h-20 animate-pulse rounded-lg bg-[#F1F0EA]" />)}</div>
        ) : orders.length === 0 ? (
          <p className="rounded-lg bg-[#F1F0EA] p-4 text-sm text-[#66727C]">{localize("Đơn chưa có dịch vụ thêm.", "No add-on services have been requested.")}</p>
        ) : (
          <div className="space-y-2">
          {orders.map((order) => {
            const image = order.imageUrl ? resolveMediaSource(order.imageUrl) : "";
            const cancelling = cancelTarget === order.id;
            return (
              <article key={order.id} className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-3">
                <div className="flex gap-3">
                  <div className="relative h-16 w-20 shrink-0 overflow-hidden rounded-lg bg-[#E5E9ED]">
                    {image && <ProgressiveImage src={image} alt={localize(order.serviceName, order.serviceNameEn)} fill sizes="80px" className="object-cover" />}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div><p className="font-bold text-[#0F2A43]">{localize(order.serviceName, order.serviceNameEn)}</p><p className="mt-0.5 text-xs text-[#66727C]">{order.quantity} × {money(order.unitPrice)} {pricingUnitLabel(order.pricingUnit, localize)}{order.pricingMultiplier > 1 ? ` · ${order.pricingMultiplier} ${localize("chu kỳ", "cycles")}` : ""}</p></div>
                      <div className="text-right"><span className={`inline-flex rounded-full border px-2 py-1 text-[10px] font-bold ${statusTone(order.status)}`}>{serviceStatusLabel(order.status, localize)}</span><p className="mt-1 text-sm font-black tabular-nums text-[#80632F]">{money(order.totalPrice)}</p></div>
                    </div>
                    {order.notes && <p className="mt-2 text-xs leading-5 text-[#66727C]">{localize("Ghi chú", "Note")}: {order.notes}</p>}
                    {order.cancellationReason && <p className="mt-2 text-xs font-semibold text-rose-700">{localize("Lý do hủy", "Cancellation reason")}: {order.cancellationReason}</p>}
                  </div>
                </div>
                {operator && (order.status === "REQUESTED" || order.status === "CONFIRMED") && (
                  <div className="mt-3 flex flex-wrap justify-end gap-2 border-t border-[#0F2A43]/10 pt-3">
                    {order.status === "REQUESTED" && <button type="button" disabled={Boolean(busyKey)} onClick={() => void updateStatus(order, "CONFIRMED")} className="min-h-10 rounded-lg bg-sky-700 px-4 text-xs font-bold text-white hover:bg-sky-800 disabled:opacity-50">{localize("Xác nhận & cộng phí", "Confirm & charge")}</button>}
                    {order.status === "CONFIRMED" && <button type="button" disabled={Boolean(busyKey)} onClick={() => void updateStatus(order, "FULFILLED")} className="min-h-10 rounded-lg bg-emerald-700 px-4 text-xs font-bold text-white hover:bg-emerald-800 disabled:opacity-50">{localize("Đã phục vụ", "Mark fulfilled")}</button>}
                    <button type="button" disabled={Boolean(busyKey)} onClick={() => { setCancelTarget(cancelling ? null : order.id); setCancellationReason(""); setError(""); }} className="min-h-10 rounded-lg border border-rose-200 bg-white px-4 text-xs font-bold text-rose-700 hover:bg-rose-50 disabled:opacity-50">{localize("Hủy dịch vụ", "Cancel service")}</button>
                  </div>
                )}
                {operator && cancelling && (
                  <div className="mt-3 flex flex-col gap-2 rounded-lg border border-rose-200 bg-rose-50 p-3 sm:flex-row">
                    <label className="min-w-0 flex-1 text-[10px] font-bold uppercase tracking-wider text-rose-800">{localize("Lý do hủy", "Cancellation reason")} *<input autoFocus value={cancellationReason} onChange={(event) => setCancellationReason(event.target.value.slice(0, 500))} className="mt-1 min-h-10 w-full rounded-lg border border-rose-200 bg-white px-3 text-sm font-medium normal-case text-[#0F2A43] outline-none focus:border-rose-400" /></label>
                    <button type="button" disabled={Boolean(busyKey) || !cancellationReason.trim()} onClick={() => void updateStatus(order, "CANCELLED")} className="min-h-10 self-end rounded-lg bg-rose-700 px-4 text-xs font-bold text-white disabled:opacity-50">{localize("Xác nhận hủy", "Confirm cancellation")}</button>
                  </div>
                )}
              </article>
            );
          })}
          </div>
        )}
      </div>

      {canRequest && catalog.length > 0 && (
        <div className="space-y-3 rounded-xl border border-[#B8944F]/35 bg-[#F0EADF]/55 p-4">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <p className="text-sm font-bold text-[#0F2A43]">{operator ? localize("Chọn dịch vụ cho khách", "Select services for the guest") : localize("Chọn dịch vụ trong kỳ lưu trú", "Select services during your stay")}</p>
              <p className="mt-1 max-w-2xl text-xs leading-5 text-[#66727C]">{localize("Có thể chọn nhiều dịch vụ, điều chỉnh số lượng và ghi chú riêng cho từng mục. Yêu cầu chưa làm tăng công nợ; phí chỉ được cộng khi lễ tân xác nhận.", "Select multiple services, quantities, and notes. Requests do not increase the balance until staff confirms them.")}</p>
            </div>
            {selectedCount > 0 && <span className="rounded-full bg-[#0F2A43] px-3 py-1 text-xs font-bold tabular-nums text-white">{localize(`Đã chọn ${selectedCount} · Tạm tính ${money(selectedEstimate)}`, `${selectedCount} selected · Estimate ${money(selectedEstimate)}`)}</span>}
          </div>
          <BookingAddOnSelector
            services={catalog}
            selections={selections}
            guestCount={guestCount}
            nights={estimatedPackageCycles}
            disabled={Boolean(busyKey)}
            onChange={(next) => {
              setSelections(next);
              setBatchDraftKey(`${Date.now()}-${Math.random().toString(36).slice(2)}`);
              setError("");
              setSuccess("");
            }}
          />
          <p className="rounded-lg border border-[#B8944F]/25 bg-white/70 px-3 py-2 text-xs leading-5 text-[#66727C]">{localize("Đơn giá hiển thị theo từng dịch vụ. Dịch vụ theo chu kỳ lưu trú sẽ được backend tính và chốt theo thời gian của đơn khi gửi yêu cầu.", "Unit prices are shown per service. Package-cycle services are calculated and snapshotted by the backend for this stay when submitted.")}</p>
          <button type="button" disabled={Boolean(busyKey) || selectedCount === 0} onClick={() => void requestServices()} className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-50">{busyKey.includes(":batch:") && <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-r-white" />}{operator ? localize(`Tạo ${selectedCount} yêu cầu`, `Create ${selectedCount} requests`) : localize(`Gửi ${selectedCount} yêu cầu`, `Send ${selectedCount} requests`)}</button>
        </div>
      )}
      {canRequest && !loading && catalog.length === 0 && (
        <p className="rounded-xl border border-[#B8944F]/25 bg-[#F0EADF]/55 p-4 text-sm leading-6 text-[#66727C]">{localize("Hiện chưa có dịch vụ đang hoạt động để thêm vào đơn. Lịch sử dịch vụ bên trên vẫn có thể được theo dõi và xử lý.", "No active services are currently available to add. Existing service history remains available above.")}</p>
      )}
    </section>
  );
}
