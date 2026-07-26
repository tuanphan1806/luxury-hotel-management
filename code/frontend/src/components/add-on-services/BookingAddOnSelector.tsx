"use client";

import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  type AddOnSelection,
  type AddOnServiceItem,
  calculateAddOnLineTotal,
  normalizeSelectionQuantity,
  pricingUnitLabel,
} from "@/lib/add-on-services";
import { resolveMediaSource } from "@/lib/media-url";

interface Props {
  services: AddOnServiceItem[];
  selections: Record<number, AddOnSelection>;
  guestCount: number;
  nights: number;
  loading?: boolean;
  disabled?: boolean;
  onChange: (selections: Record<number, AddOnSelection>) => void;
}

export default function BookingAddOnSelector({
  services,
  selections,
  guestCount,
  nights,
  loading = false,
  disabled = false,
  onChange,
}: Props) {
  const { localeTag, localize } = useLanguage();
  const money = (value: number) => value.toLocaleString(localeTag, {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  });

  const toggle = (service: AddOnServiceItem) => {
    if (disabled) return;
    const next = { ...selections };
    if (next[service.id]) {
      delete next[service.id];
    } else {
      next[service.id] = {
        quantity: normalizeSelectionQuantity(service, undefined, guestCount),
        notes: "",
      };
    }
    onChange(next);
  };

  const update = (service: AddOnServiceItem, patch: Partial<AddOnSelection>) => {
    const current = selections[service.id];
    if (!current || disabled) return;
    onChange({
      ...selections,
      [service.id]: {
        ...current,
        ...patch,
        quantity: patch.quantity === undefined
          ? current.quantity
          : normalizeSelectionQuantity(service, patch.quantity, guestCount),
      },
    });
  };

  if (loading) {
    return (
      <div className="grid gap-3 sm:grid-cols-2" role="status" aria-label={localize("Đang tải dịch vụ", "Loading services")}>
        {[1, 2, 3, 4].map((item) => <div key={item} className="h-40 animate-pulse rounded-xl bg-[#E5E9ED]" />)}
      </div>
    );
  }

  if (services.length === 0) {
    return <p className="rounded-xl bg-[#F1F0EA] p-4 text-sm text-[#66727C]">{localize("Hiện chưa có dịch vụ đặt trước.", "No pre-bookable services are available.")}</p>;
  }

  return (
    <div className="grid gap-3 sm:grid-cols-2">
      {services.map((service) => {
        const selected = selections[service.id];
        const image = service.imageUrl ? resolveMediaSource(service.imageUrl) : "";
        const lineTotal = selected
          ? calculateAddOnLineTotal(service, selected, guestCount, nights)
          : 0;
        const maxQuantity = service.pricingUnit === "PER_GUEST" ? Math.max(1, guestCount) : 99;
        const quantityLocked = service.pricingUnit === "PER_ORDER";
        return (
          <article
            key={service.id}
            className={`overflow-hidden rounded-xl border bg-white transition duration-200 ${selected ? "border-[#B8944F] shadow-[0_10px_30px_rgba(15,42,67,0.12)] ring-1 ring-[#B8944F]/35" : "border-[#0F2A43]/12 hover:-translate-y-0.5 hover:border-[#B8944F]/70 hover:shadow-md"}`}
          >
            <button
              type="button"
              disabled={disabled}
              aria-pressed={Boolean(selected)}
              onClick={() => toggle(service)}
              className="grid min-h-[8.5rem] w-full cursor-pointer grid-cols-[7rem_1fr] text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-60"
            >
              <div className="relative h-full min-h-[8.5rem] bg-[#E5E9ED]">
                {image ? (
                  <ProgressiveImage
                    src={image}
                    alt={localize(service.name, service.nameEn)}
                    fill
                    sizes="112px"
                    className="object-cover"
                  />
                ) : (
                  <span className="flex h-full items-center justify-center text-xs font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</span>
                )}
              </div>
              <div className="flex min-w-0 flex-col p-3">
                <div className="flex items-start justify-between gap-2">
                  <h4 className="font-serif text-base font-bold text-[#0F2A43]">{localize(service.name, service.nameEn)}</h4>
                  <span aria-hidden="true" className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border text-xs font-black ${selected ? "border-[#B8944F] bg-[#B8944F] text-[#0F2A43]" : "border-[#0F2A43]/25 text-transparent"}`}>✓</span>
                </div>
                <p className="mt-1 line-clamp-2 text-xs leading-5 text-[#66727C]">{localize(service.description, service.descriptionEn)}</p>
                <p className="mt-auto pt-2 text-sm font-black tabular-nums text-[#80632F]">{money(service.price)} <span className="text-[10px] font-semibold text-[#66727C]">{pricingUnitLabel(service.pricingUnit, localize)}</span></p>
              </div>
            </button>
            {selected && (
              <div className="grid gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] p-3 sm:grid-cols-[7.5rem_1fr]">
                <label className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">
                  {localize("Số lượng", "Quantity")}
                  <input
                    type="number"
                    min={1}
                    max={maxQuantity}
                    disabled={disabled || quantityLocked}
                    value={selected.quantity}
                    onChange={(event) => update(service, { quantity: Number(event.target.value) })}
                    className="mt-1 min-h-10 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-bold text-[#0F2A43] outline-none focus:border-[#B8944F] disabled:bg-[#E5E9ED]"
                  />
                </label>
                <label className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">
                  {localize("Ghi chú", "Note")}
                  <input
                    type="text"
                    maxLength={1000}
                    disabled={disabled}
                    value={selected.notes}
                    onChange={(event) => update(service, { notes: event.target.value })}
                    placeholder={localize("Yêu cầu chuẩn bị cụ thể", "Preparation note")}
                    className="mt-1 min-h-10 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-medium normal-case text-[#0F2A43] outline-none focus:border-[#B8944F]"
                  />
                </label>
                <p className="text-xs font-bold text-[#0F2A43] sm:col-span-2 sm:text-right">{localize("Tạm tính", "Subtotal")}: <span className="text-[#80632F]">{money(lineTotal)}</span></p>
              </div>
            )}
          </article>
        );
      })}
    </div>
  );
}
