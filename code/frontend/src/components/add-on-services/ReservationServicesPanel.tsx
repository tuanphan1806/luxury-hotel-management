"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import {
  type AddOnServiceItem,
  type ReservationServiceItem,
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
  initialServices = [],
  operator = false,
  onChanged,
}: Props) {
  const { localeTag, localize } = useLanguage();
  const [orders, setOrders] = useState<ReservationServiceItem[]>(initialServices);
  const [catalog, setCatalog] = useState<AddOnServiceItem[]>([]);
  const [selectedServiceId, setSelectedServiceId] = useState("");
  const [quantity, setQuantity] = useState("1");
  const [notes, setNotes] = useState("");
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
      const [ordersResponse, catalogItems] = await Promise.all([
        apiClient.get(`/api/reservations/${reservationId}/services`),
        canRequest ? getAddOnCatalog("IN_STAY") : Promise.resolve([]),
      ]);
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
      setCatalog(catalogItems);
      setSelectedServiceId((current) => current || String(catalogItems[0]?.id || ""));
    } catch (loadError: unknown) {
      setError(getApiErrorMessage(loadError, localize("Không thể tải dịch vụ của đơn.", "Could not load reservation services.")));
    } finally {
      setLoading(false);
    }
  }, [canRequest, localize, reservationId]);

  useEffect(() => {
    void load();
  }, [load]);

  const selectedService = catalog.find((item) => item.id === Number(selectedServiceId));
  const selectedQuantityLimit = selectedService?.pricingUnit === "PER_GUEST"
    ? Math.max(1, guestCount)
    : 99;
  const committedTotal = useMemo(() => orders
    .filter((item) => item.status === "CONFIRMED" || item.status === "FULFILLED")
    .reduce((sum, item) => sum + item.totalPrice, 0), [orders]);

  const requestService = async () => {
    if (!selectedService) {
      setError(localize("Vui lòng chọn dịch vụ.", "Select a service."));
      return;
    }
    const normalizedQuantity = selectedService.pricingUnit === "PER_ORDER" ? 1 : Number(quantity);
    if (!Number.isInteger(normalizedQuantity)
        || normalizedQuantity < 1
        || normalizedQuantity > selectedQuantityLimit) {
      setError(selectedService.pricingUnit === "PER_GUEST"
        ? localize(
            `Số suất phải từ 1 đến tổng số ${selectedQuantityLimit} khách của đơn.`,
            `Quantity must be between 1 and the reservation's ${selectedQuantityLimit} guests.`,
          )
        : localize("Số lượng dịch vụ phải từ 1 đến 99.", "Quantity must be from 1 to 99."));
      return;
    }
    const scope = `reservation-service:${reservationId}:request:${selectedService.id}`;
    setBusyKey(scope);
    setError("");
    setSuccess("");
    try {
      await apiClient.post(`/api/reservations/${reservationId}/services`, {
        serviceId: selectedService.id,
        quantity: normalizedQuantity,
        notes: notes.trim() || undefined,
      }, {
        headers: { "Idempotency-Key": getOrCreateIdempotencyKey(scope) },
      });
      clearIdempotencyKey(scope);
      setNotes("");
      setQuantity("1");
      setSuccess(operator
        ? localize("Đã tạo yêu cầu; hãy xác nhận trước khi khoản phí được cộng.", "Request created; confirm it before the charge is added.")
        : localize("Đã gửi yêu cầu tới lễ tân.", "The request was sent to the front desk."));
      await load();
      await onChanged?.();
    } catch (requestError: unknown) {
      setError(getApiErrorMessage(requestError, localize("Không thể gửi yêu cầu dịch vụ.", "Could not request the service.")));
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
    <section className="space-y-4 rounded-xl border border-[#0F2A43]/12 bg-white p-4 sm:p-5">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-[#0F2A43]/10 pb-3">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Dịch vụ thêm", "Add-on services")}</p>
          <h3 className="mt-1 font-serif text-lg font-bold text-[#0F2A43]">{reservationCode || `#${reservationId}`}</h3>
        </div>
        <div className="text-right"><p className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Phí đã xác nhận", "Committed charges")}</p><p className="mt-1 font-black tabular-nums text-[#80632F]">{money(committedTotal)}</p></div>
      </div>

      {error && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 p-3 text-sm font-semibold text-rose-700">{error}</p>}
      {success && <p role="status" className="rounded-lg border border-emerald-200 bg-emerald-50 p-3 text-sm font-semibold text-emerald-800">{success}</p>}

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

      {canRequest && catalog.length > 0 && (
        <div className="space-y-3 rounded-xl border border-[#B8944F]/35 bg-[#F0EADF]/55 p-4">
          <div><p className="text-sm font-bold text-[#0F2A43]">{operator ? localize("Thêm yêu cầu cho khách", "Add a request for the guest") : localize("Yêu cầu dịch vụ trong kỳ lưu trú", "Request a service during your stay")}</p><p className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Yêu cầu chưa làm tăng công nợ; phí chỉ được cộng khi lễ tân xác nhận.", "A request does not increase the balance until staff confirms it.")}</p></div>
          <div className="grid gap-3 sm:grid-cols-[1fr_7rem]">
            <label className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Dịch vụ", "Service")}<select value={selectedServiceId} onChange={(event) => { setSelectedServiceId(event.target.value); setQuantity("1"); setError(""); }} className="mt-1 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-semibold normal-case text-[#0F2A43] outline-none focus:border-[#B8944F]">{catalog.map((item) => <option key={item.id} value={item.id}>{localize(item.name, item.nameEn)} · {money(item.price)} {pricingUnitLabel(item.pricingUnit, localize)}</option>)}</select></label>
            <label className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Số lượng", "Quantity")}<input type="number" min={1} max={selectedQuantityLimit} disabled={selectedService?.pricingUnit === "PER_ORDER"} value={selectedService?.pricingUnit === "PER_ORDER" ? 1 : quantity} onChange={(event) => { setQuantity(event.target.value); setError(""); }} className="mt-1 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-bold normal-case text-[#0F2A43] outline-none focus:border-[#B8944F] disabled:bg-[#E5E9ED]" /></label>
          </div>
          <label className="block text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Ghi chú chuẩn bị", "Preparation note")}<textarea rows={2} maxLength={1000} value={notes} onChange={(event) => setNotes(event.target.value)} className="mt-1 w-full resize-none rounded-lg border border-[#0F2A43]/15 bg-white px-3 py-2 text-sm font-medium normal-case text-[#0F2A43] outline-none focus:border-[#B8944F]" /></label>
          <button type="button" disabled={Boolean(busyKey)} onClick={() => void requestService()} className="inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30] disabled:cursor-wait disabled:opacity-50">{busyKey.includes(":request:") && <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-r-white" />}{operator ? localize("Tạo yêu cầu", "Create request") : localize("Gửi yêu cầu", "Send request")}</button>
        </div>
      )}
    </section>
  );
}
