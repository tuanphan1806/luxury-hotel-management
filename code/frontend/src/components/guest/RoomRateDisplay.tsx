"use client";

import { useLanguage } from "@/components/i18n/LanguageProvider";

export interface PublicRoomRate {
  packagePricingEnabled?: boolean;
  pricingAvailable?: boolean;
  includedGuests?: number;
  maxGuests?: number;
  firstBlockMinutes?: number;
  firstBlockPrice?: number | null;
  extraUnitMinutes?: number;
  extraUnitPrice?: number | null;
  overnightPrice?: number | null;
  dailyPrice?: number | null;
  extraGuestPrice?: number | null;
  estimatedPricePerRoom?: number | null;
  estimatedPackage?: "HOURLY" | "OVERNIGHT" | "DAILY" | null;
  totalHours?: number | null;
}

interface RoomRateCompactProps {
  rate: PublicRoomRate;
  className?: string;
  display?: "published" | "stay-estimate";
}

interface RoomRatePanelProps {
  rate: PublicRoomRate;
  className?: string;
}

export const publicRateAmount = (value?: number | string | null) => {
  if (value == null || value === "") return undefined;
  const amount = Number(value);
  return Number.isFinite(amount) ? amount : undefined;
};

const formatVND = (value?: number | string | null) => {
  const amount = publicRateAmount(value);
  return amount == null
    ? "—"
    : amount.toLocaleString("vi-VN", {
        style: "currency",
        currency: "VND",
        maximumFractionDigits: 0,
      });
};

export const comparablePublicRoomPrice = (rate: PublicRoomRate) =>
  publicRateAmount(rate.overnightPrice) ?? 0;

export const compactStayRate = (
  rate: PublicRoomRate,
  display: RoomRateCompactProps["display"] = "published",
) => {
  if (display === "stay-estimate") {
    const amount = publicRateAmount(rate.estimatedPricePerRoom);
    if (amount != null && rate.estimatedPackage) {
      return {
        amount,
        packageCode: rate.estimatedPackage,
        totalHours: publicRateAmount(rate.totalHours),
      };
    }
  }

  const amount = publicRateAmount(rate.overnightPrice);
  return amount == null
    ? null
    : { amount, packageCode: "OVERNIGHT" as const, totalHours: undefined };
};

export function RoomRateCompact({ rate, className = "", display = "published" }: RoomRateCompactProps) {
  const { localize } = useLanguage();
  const compactRate = compactStayRate(rate, display);

  if (!compactRate) {
    return null;
  }

  const packageLabel = compactRate.packageCode === "HOURLY"
    ? localize("Nghỉ giờ", "Hourly stay")
    : compactRate.packageCode === "DAILY"
      ? localize("Ngày đêm", "Daily stay")
      : localize("Qua đêm", "Overnight stay");
  const note = display === "stay-estimate"
    ? [
        compactRate.totalHours != null
          ? localize(`${compactRate.totalHours} giờ`, `${compactRate.totalHours} hours`)
          : null,
        packageLabel,
      ].filter(Boolean).join(" · ")
    : localize("20:00–08:00 · trả trước 10:00", "20:00–08:00 · checkout by 10:00");

  return (
    <div className={`min-w-[10.5rem] rounded-xl border border-white/75 bg-[#FBFAF6]/96 px-3.5 py-2.5 text-[#0F2A43] shadow-[0_10px_28px_rgba(9,30,48,0.18)] backdrop-blur-sm ${className}`}>
      <p className="text-[9px] font-extrabold uppercase tracking-[0.16em] text-[#80632F]">
        {display === "stay-estimate"
          ? localize("Giá kỳ lưu trú", "Selected stay price")
          : localize("Giá qua đêm", "Overnight rate")}
      </p>
      <strong className="mt-0.5 block font-sans text-lg font-extrabold tabular-nums tracking-[-0.02em]">
        {formatVND(compactRate.amount)}
      </strong>
      <p className="mt-1 text-[9px] font-bold text-[#66727C]">{note}</p>
    </div>
  );
}

export function RoomRatePanel({ rate, className = "" }: RoomRatePanelProps) {
  const { localize } = useLanguage();
  const includedGuests = Math.max(1, Number(rate.includedGuests || rate.maxGuests || 1));
  const maxGuests = Math.max(includedGuests, Number(rate.maxGuests || includedGuests));
  const extraGuestPrice = publicRateAmount(rate.extraGuestPrice);
  const guestSurchargeLabel = maxGuests === includedGuests + 1
    ? localize(
        `Khách thứ ${maxGuests}: phụ thu ${formatVND(extraGuestPrice)}/người/mỗi chu kỳ lưu trú.`,
        `Guest ${maxGuests}: ${formatVND(extraGuestPrice)} per extra guest per stay cycle.`,
      )
    : localize(
        `Từ khách thứ ${includedGuests + 1} đến khách thứ ${maxGuests}: phụ thu ${formatVND(extraGuestPrice)}/người/mỗi chu kỳ lưu trú.`,
        `Guests ${includedGuests + 1} to ${maxGuests}: ${formatVND(extraGuestPrice)} per extra guest per stay cycle.`,
      );
  const packageRateReady = Boolean(
    publicRateAmount(rate.overnightPrice) != null
      && publicRateAmount(rate.dailyPrice) != null,
  );

  if (!packageRateReady) {
    return (
      <section className={`rounded-2xl border border-[#0F2A43]/10 bg-[#F7F4EC] p-4 ${className}`}>
        <p className="text-[10px] font-extrabold uppercase tracking-[0.18em] text-[#80632F]">
          {localize("Mức giá của hạng phòng", "Room type rates")}
        </p>
        <p className="mt-2 text-xs font-medium leading-5 text-[#66727C]">
          {localize("Mức giá qua đêm và ngày đêm của hạng phòng này đang được cập nhật.", "The overnight and daily rates for this room type are being updated.")}
        </p>
      </section>
    );
  }

  const rows = [
    {
      key: "overnight",
      label: localize("Qua đêm", "Overnight"),
      price: rate.overnightPrice,
      unit: localize("mỗi kỳ", "per stay"),
      note: localize("Khung đêm 20:00–08:00 · trả muộn nhất 10:00", "20:00–08:00 overnight window · checkout by 10:00"),
    },
    {
      key: "daily",
      label: localize("Ngày đêm", "Daily"),
      price: rate.dailyPrice,
      unit: "24 giờ",
      note: localize("Tính theo từng chu kỳ 24 giờ", "Charged in rolling 24-hour cycles"),
    },
  ];

  return (
    <section className={`overflow-hidden rounded-2xl border border-[#0F2A43]/12 bg-[#FBFAF6] ${className}`}>
      <div className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 bg-[#F1F0EA] px-4 py-3.5">
        <div>
          <p className="text-[9px] font-extrabold uppercase tracking-[0.18em] text-[#80632F]">{localize("Mức giá của hạng phòng", "Room type rates")}</p>
          <h4 className="mt-1 font-serif text-lg font-bold text-[#0F2A43]">{localize("Qua đêm và ngày đêm", "Overnight and daily stays")}</h4>
        </div>
        <span className="shrink-0 rounded-full border border-[#B8944F]/35 bg-white px-2.5 py-1 text-[9px] font-extrabold uppercase tracking-[0.12em] text-[#80632F]">
          {localize("Giá niêm yết", "Published")}
        </span>
      </div>

      <div className="divide-y divide-[#0F2A43]/9">
        {rows.map((row) => (
          <div key={row.key} className="grid grid-cols-[minmax(0,1fr)_auto] gap-4 px-4 py-3.5 transition-colors hover:bg-[#F7F4EC]">
            <div>
              <p className="text-xs font-extrabold uppercase tracking-[0.12em] text-[#0F2A43]">{row.label}</p>
              <p className="mt-1 text-[11px] font-medium leading-5 text-[#66727C]">{row.note}</p>
            </div>
            <div className="text-right">
              <p className="font-sans text-lg font-extrabold tabular-nums tracking-[-0.02em] text-[#80632F]">{formatVND(row.price)}</p>
              <p className="mt-0.5 text-[10px] font-bold text-[#66727C]">/ {row.unit}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="border-t border-[#0F2A43]/10 bg-[#E8EFEA] px-4 py-3 text-xs font-semibold leading-5 text-[#315746]">
        <p>
          {localize(
            `Mức giá trên đã gồm ${includedGuests} khách/phòng. Sức chứa tối đa ${maxGuests} khách/phòng.`,
            `Published rates include ${includedGuests} guests per room. Maximum occupancy is ${maxGuests} guests per room.`,
          )}
        </p>
        {maxGuests > includedGuests && extraGuestPrice != null && (
          <p className="mt-1 text-[#80632F]">
            {guestSurchargeLabel}
          </p>
        )}
        <p className="mt-1 text-[11px] font-medium text-[#527060]">
          {localize(
            "Giá chính xác được tính sau khi chọn thời gian, số phòng và phân bổ khách.",
            "The exact amount is calculated after dates, rooms and guest allocation are selected.",
          )}
        </p>
      </div>
    </section>
  );
}
