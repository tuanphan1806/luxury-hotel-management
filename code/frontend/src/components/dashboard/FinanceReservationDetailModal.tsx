"use client";

import Link from "next/link";
import ViewportModal from "@/components/UI/ViewportModal";
import type { ReservationMoneyEntry } from "@/lib/business-statistics";

export type FinanceReservationDetail = {
  id: number;
  reservationCode: string;
  customerName?: string;
  customerPhone?: string;
  customerEmail?: string;
  checkIn: string;
  checkOut: string;
  actualCheckIn?: string;
  actualCheckOut?: string;
  totalAmount?: number;
  actualTotalAmount?: number;
  plannedRoomCharge?: number;
  actualRoomCharge?: number;
  extraGuestCharge?: number;
  addOnServiceAmount?: number;
  checkoutAdditionalFee?: number;
  lateCheckoutFee?: number;
  discountAmount?: number;
  taxAmount?: number;
  paidAmount?: number;
  guestCount?: number;
  status: string;
  roomTypes?: Array<{
    id: number;
    roomTypeId: number;
    roomTypeName: string;
    quantity: number;
    actualSubtotal?: number;
    subtotal?: number;
  }>;
  rooms?: Array<{
    id: number;
    reservationRoomTypeId: number;
    roomName?: string;
    status?: string;
  }>;
  services?: Array<{
    id: number;
    serviceName: string;
    quantity: number;
    totalPrice: number;
    status: string;
  }>;
};

type Props = {
  selected: ReservationMoneyEntry | null;
  detail: FinanceReservationDetail | null;
  loading: boolean;
  error: string;
  localeTag: string;
  onClose: () => void;
  onRetry: () => void;
};

const statusLabel = (status: string) => ({
  DRAFT: "Chờ xác nhận",
  PAYMENT_PENDING: "Chờ thanh toán",
  CONFIRMED: "Đã xác nhận",
  CANCELLATION_PENDING: "Chờ duyệt hủy",
  CANCELLED: "Đã hủy",
  CHECKED_IN: "Đang lưu trú",
  CHECKED_OUT: "Đã trả phòng",
  NO_SHOW: "Không đến",
}[status] || status.replaceAll("_", " "));

const vnd = (value: number, localeTag: string) => new Intl.NumberFormat(
  localeTag,
  {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  },
).format(Number(value || 0));

const localDateTime = (
  value: string | undefined,
  localeTag: string,
  fallback = "Chưa ghi nhận",
) => {
  if (!value) return fallback;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return fallback;
  return date.toLocaleString(localeTag, {
    dateStyle: "short",
    timeStyle: "short",
    timeZone: "Asia/Ho_Chi_Minh",
  });
};

export default function FinanceReservationDetailModal({
  selected,
  detail,
  loading,
  error,
  localeTag,
  onClose,
  onRetry,
}: Props) {
  const amounts = selected?.amounts;
  const obligation = Number(detail?.actualTotalAmount ?? detail?.totalAmount ?? 0);
  const paid = Number(detail?.paidAmount ?? amounts?.totalIncome ?? 0);

  return (
    <ViewportModal
      open={Boolean(selected)}
      onClose={onClose}
      labelledBy="finance-reservation-detail-title"
      panelClassName="max-w-5xl"
      testId="finance-reservation-detail-modal"
    >
      <header className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
        <div className="min-w-0">
          <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#B8944F]">
            Chi tiết thu chi theo đơn
          </p>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <h2 id="finance-reservation-detail-title" className="font-serif text-2xl font-bold text-[#0F2A43]">
              {selected?.reservationCode}
            </h2>
            {selected && (
              <span className="rounded-full border border-[#0F2A43]/10 bg-white px-2.5 py-1 text-[10px] font-bold text-[#0F2A43]">
                {statusLabel(selected.reservationStatus)}
              </span>
            )}
          </div>
        </div>
        <button
          type="button"
          onClick={onClose}
          aria-label="Đóng chi tiết đơn"
          className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-[#0F2A43]/10 text-xl text-[#66727C] transition hover:bg-white hover:text-[#0F2A43] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
        >
          ×
        </button>
      </header>

      <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto px-5 py-5 sm:px-6">
        {loading ? (
          <div className="grid gap-4 md:grid-cols-2">
            {[1, 2, 3, 4].map((item) => (
              <div key={item} className="h-32 animate-pulse rounded-xl bg-[#0F2A43]/7" />
            ))}
          </div>
        ) : error ? (
          <div role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-5 text-sm text-rose-800">
            <p className="font-bold">{error}</p>
            <button
              type="button"
              onClick={onRetry}
              className="mt-3 min-h-10 rounded-lg bg-rose-700 px-4 font-bold text-white transition hover:bg-rose-800"
            >
              Thử lại
            </button>
          </div>
        ) : detail && amounts ? (
          <div className="space-y-5">
            <section className="grid gap-3 md:grid-cols-2">
              <article className="rounded-xl border border-[#0F2A43]/10 bg-white p-4">
                <p className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#B8944F]">
                  Khách đặt phòng
                </p>
                <h3 className="mt-2 text-lg font-bold text-[#0F2A43]">
                  {detail.customerName || "Chưa có tên khách"}
                </h3>
                <div className="mt-2 space-y-1 text-sm text-[#66727C]">
                  <p>{detail.customerPhone || "Chưa có số điện thoại"}</p>
                  <p className="break-all">{detail.customerEmail || "Chưa có email"}</p>
                  <p>{Number(detail.guestCount || 0).toLocaleString(localeTag)} khách</p>
                </div>
              </article>
              <article className="rounded-xl border border-[#0F2A43]/10 bg-white p-4">
                <p className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#B8944F]">
                  Thời gian lưu trú
                </p>
                <dl className="mt-2 grid gap-2 text-sm sm:grid-cols-2">
                  <div>
                    <dt className="text-[#7A858E]">Dự kiến nhận</dt>
                    <dd className="mt-1 font-bold text-[#0F2A43]">{localDateTime(detail.checkIn, localeTag)}</dd>
                  </div>
                  <div>
                    <dt className="text-[#7A858E]">Dự kiến trả</dt>
                    <dd className="mt-1 font-bold text-[#0F2A43]">{localDateTime(detail.checkOut, localeTag)}</dd>
                  </div>
                  <div>
                    <dt className="text-[#7A858E]">Nhận thực tế</dt>
                    <dd className="mt-1 font-bold text-[#0F2A43]">{localDateTime(detail.actualCheckIn, localeTag)}</dd>
                  </div>
                  <div>
                    <dt className="text-[#7A858E]">Trả thực tế</dt>
                    <dd className="mt-1 font-bold text-[#0F2A43]">{localDateTime(detail.actualCheckOut, localeTag)}</dd>
                  </div>
                </dl>
              </article>
            </section>

            <section className="rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <p className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#B8944F]">Phòng thuộc đơn</p>
                  <h3 className="mt-1 text-lg font-bold text-[#0F2A43]">Hạng phòng và phòng đã gán</h3>
                </div>
                <span className="text-xs font-semibold text-[#66727C]">
                  {(detail.roomTypes || []).reduce((sum, item) => sum + Number(item.quantity || 0), 0)} phòng
                </span>
              </div>
              <div className="mt-3 grid gap-3 md:grid-cols-2">
                {(detail.roomTypes || []).map((roomType) => {
                  const roomNames = (detail.rooms || [])
                    .filter((room) => room.reservationRoomTypeId === roomType.id)
                    .map((room) => room.roomName)
                    .filter(Boolean);
                  return (
                    <article key={roomType.id} className="rounded-lg border border-[#0F2A43]/10 bg-white p-3">
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <p className="font-bold text-[#0F2A43]">{roomType.roomTypeName}</p>
                          <p className="mt-1 text-xs text-[#66727C]">
                            {roomNames.length > 0 ? roomNames.join(", ") : "Chưa gán số phòng"}
                          </p>
                        </div>
                        <span className="rounded-full bg-[#F1F0EA] px-2 py-1 text-[10px] font-bold text-[#0F2A43]">
                          × {roomType.quantity}
                        </span>
                      </div>
                      <p className="mt-3 text-right font-bold text-[#0F2A43]">
                        {vnd(Number(roomType.actualSubtotal ?? roomType.subtotal ?? 0), localeTag)}
                      </p>
                    </article>
                  );
                })}
              </div>
            </section>

            <section className="grid gap-4 xl:grid-cols-[1.1fr_0.9fr]">
              <article className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white">
                <div className="border-b border-[#0F2A43]/10 px-4 py-3">
                  <h3 className="font-bold text-[#0F2A43]">Thu và hoàn tiền</h3>
                </div>
                <dl className="grid grid-cols-2 gap-px bg-[#0F2A43]/10 text-sm sm:grid-cols-3">
                  {[
                    ["Thu tiền mặt", amounts.cashIncome, "text-emerald-700"],
                    ["Thu chuyển khoản", amounts.transferIncome, "text-emerald-700"],
                    ["Tổng đã thu", amounts.totalIncome, "text-emerald-800"],
                    ["Hoàn tiền mặt", amounts.cashRefund, "text-rose-700"],
                    ["Hoàn chuyển khoản", amounts.transferRefund, "text-rose-700"],
                    ["Doanh thu thực nhận", amounts.netRevenue, "text-[#0F2A43]"],
                  ].map(([label, value, color]) => (
                    <div key={String(label)} className="bg-white p-3">
                      <dt className="text-xs text-[#66727C]">{label}</dt>
                      <dd className={`mt-1 font-bold ${color}`}>{vnd(Number(value), localeTag)}</dd>
                    </div>
                  ))}
                </dl>
              </article>

              <article className="rounded-xl border border-[#0F2A43]/10 bg-[#0F2A43] p-4 text-white">
                <p className="text-[10px] font-extrabold uppercase tracking-[0.14em] text-[#D8C398]">Nghĩa vụ thanh toán</p>
                <dl className="mt-3 space-y-3 text-sm">
                  <div className="flex justify-between gap-3">
                    <dt className="text-white/70">Tổng cần thanh toán</dt>
                    <dd className="font-bold">{vnd(obligation, localeTag)}</dd>
                  </div>
                  <div className="flex justify-between gap-3">
                    <dt className="text-white/70">Đã ghi nhận thanh toán</dt>
                    <dd className="font-bold">{vnd(paid, localeTag)}</dd>
                  </div>
                  <div className="flex justify-between gap-3 border-t border-white/15 pt-3">
                    <dt className="font-bold">Còn cần thu</dt>
                    <dd className="font-serif text-xl font-bold text-[#D8C398]">
                      {vnd(Math.max(0, obligation - paid), localeTag)}
                    </dd>
                  </div>
                </dl>
              </article>
            </section>

            <section className="grid gap-4 md:grid-cols-2">
              <article className="rounded-xl border border-[#0F2A43]/10 bg-white p-4">
                <h3 className="font-bold text-[#0F2A43]">Cấu thành chi phí</h3>
                <dl className="mt-3 space-y-2 text-sm">
                  {[
                    ["Tiền phòng dự kiến", detail.plannedRoomCharge],
                    ["Tiền phòng thực tế", detail.actualRoomCharge],
                    ["Phụ thu khách thêm", detail.extraGuestCharge],
                    ["Dịch vụ thêm", detail.addOnServiceAmount],
                    ["Phụ phí khác", detail.checkoutAdditionalFee],
                    ["Phí trả muộn", detail.lateCheckoutFee],
                    ["Giảm giá", -Number(detail.discountAmount || 0)],
                    ["Thuế", detail.taxAmount],
                  ].map(([label, value]) => (
                    <div key={String(label)} className="flex justify-between gap-3 border-b border-[#0F2A43]/7 pb-2 last:border-0">
                      <dt className="text-[#66727C]">{label}</dt>
                      <dd className="font-bold text-[#0F2A43]">{vnd(Number(value || 0), localeTag)}</dd>
                    </div>
                  ))}
                </dl>
              </article>
              <article className="rounded-xl border border-[#0F2A43]/10 bg-white p-4">
                <h3 className="font-bold text-[#0F2A43]">Dịch vụ thêm</h3>
                {(detail.services || []).length === 0 ? (
                  <p className="mt-3 text-sm text-[#66727C]">Đơn không phát sinh dịch vụ thêm.</p>
                ) : (
                  <div className="mt-3 space-y-2">
                    {(detail.services || []).map((service) => (
                      <div key={service.id} className="flex items-start justify-between gap-3 rounded-lg bg-[#F8F6F0] p-3 text-sm">
                        <div>
                          <p className="font-bold text-[#0F2A43]">{service.serviceName}</p>
                          <p className="mt-1 text-xs text-[#66727C]">× {service.quantity} · {service.status}</p>
                        </div>
                        <p className="font-bold text-[#0F2A43]">{vnd(service.totalPrice, localeTag)}</p>
                      </div>
                    ))}
                  </div>
                )}
              </article>
            </section>
          </div>
        ) : null}
      </div>

      <footer className="flex flex-wrap items-center justify-between gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
        <p className="text-xs text-[#66727C]">
          Phát sinh gần nhất {localDateTime(selected?.lastMovementAtUtc, localeTag)}
        </p>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={onClose}
            className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-4 text-sm font-bold text-[#0F2A43] transition hover:bg-white"
          >
            Đóng
          </button>
          {selected && (
            <Link
              data-modal-autofocus
              href={`/dashboard/reservations?reservationCode=${encodeURIComponent(selected.reservationCode)}`}
              className="inline-flex min-h-11 items-center rounded-lg bg-[#0F2A43] px-4 text-sm font-bold text-white transition hover:bg-[#173D5F]"
            >
              Mở trang vận hành đơn
            </Link>
          )}
        </div>
      </footer>
    </ViewportModal>
  );
}
