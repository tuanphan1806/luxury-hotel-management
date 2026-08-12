import { publicApiClient } from "@/lib/api";

export type AddOnServiceFlow = "BOOKING_TIME" | "IN_STAY";
export type AddOnPricingUnit =
  | "PER_GUEST"
  | "PER_PACKAGE_CYCLE"
  | "PER_NIGHT"
  | "PER_ITEM"
  | "PER_ORDER"
  | "PER_USE";
export type AddOnServiceCategory = "FOOD_BEVERAGE" | "AMENITY" | "EQUIPMENT" | "DECORATION" | "OTHER";
export type ReservationServiceOrigin = "BOOKING_TIME" | "IN_STAY";
export type ReservationServiceStatus = "REQUESTED" | "CONFIRMED" | "FULFILLED" | "CANCELLED";

export interface AddOnServiceItem {
  id: number;
  code: string;
  name: string;
  nameEn?: string;
  description?: string;
  descriptionEn?: string;
  imageUrl?: string;
  category: AddOnServiceCategory;
  price: number;
  pricingUnit: AddOnPricingUnit;
  bookingEnabled: boolean;
  inStayEnabled: boolean;
  active: boolean;
  sortOrder: number;
}

export interface ReservationServiceItem {
  id: number;
  reservationId: number;
  serviceId: number;
  serviceCode: string;
  serviceName: string;
  serviceNameEn?: string;
  imageUrl?: string;
  unitPrice: number;
  pricingUnit: AddOnPricingUnit;
  quantity: number;
  pricingMultiplier: number;
  billableQuantity: number;
  totalPrice: number;
  origin: ReservationServiceOrigin;
  status: ReservationServiceStatus;
  notes?: string;
  cancellationReason?: string;
  requestedAtUtc: string;
  confirmedAtUtc?: string;
  fulfilledAtUtc?: string;
  cancelledAtUtc?: string;
}

export interface AddOnSelection {
  quantity: number;
  notes: string;
}

const OPERATIONAL_RESERVATION_STATUSES = new Set(["CONFIRMED", "CHECKED_IN"]);
const PENDING_OPERATIONAL_SERVICE_STATUSES = new Set<ReservationServiceStatus>([
  "REQUESTED",
  "CONFIRMED",
]);

export const isOperationalServiceQueueReservation = (
  reservationStatus: string,
  services?: ReadonlyArray<Pick<ReservationServiceItem, "status">>,
) => OPERATIONAL_RESERVATION_STATUSES.has(reservationStatus)
  && Boolean(services?.some((service) => PENDING_OPERATIONAL_SERVICE_STATUSES.has(service.status)));

export const getAddOnCatalog = async (flow: AddOnServiceFlow): Promise<AddOnServiceItem[]> => {
  const response = await publicApiClient.get("/api/add-on-services", { params: { flow } });
  const payload = response.data?.data ?? response.data;
  return Array.isArray(payload)
    ? payload.map((item) => ({
        ...item,
        id: Number(item.id),
        price: Number(item.price || 0),
      }))
    : [];
};

const wallClockMilliseconds = (value: string) => {
  const match = value.match(
    /^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?/,
  );
  if (!match) return Number.NaN;
  const [, year, month, day, hour, minute, second = "0"] = match;
  return Date.UTC(
    Number(year),
    Number(month) - 1,
    Number(day),
    Number(hour),
    Number(minute),
    Number(second),
  );
};

/**
 * Display estimator for Pricing V2 package cycles.
 *
 * The backend quote remains authoritative. This mirrors its rolling 24-hour
 * boundary and 15-minute grace so package-cycle service previews do not charge
 * a second cycle at 24h15. Local date-times are parsed as hotel wall-clock
 * values, avoiding browser timezone and DST shifts.
 */
export const chargeableNights = (checkIn?: string, checkOut?: string) => {
  if (!checkIn || !checkOut) return 1;
  const start = wallClockMilliseconds(checkIn);
  const end = wallClockMilliseconds(checkOut);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return 1;
  const minutes = Math.ceil((end - start) / 60_000);
  const fullDays = Math.floor(minutes / 1_440);
  if (fullDays === 0) return 1;
  const remainderMinutes = minutes % 1_440;
  return fullDays + (remainderMinutes > 15 ? 1 : 0);
};

export const normalizeSelectionQuantity = (
  service: AddOnServiceItem,
  requested: number | undefined,
  guestCount: number,
) => {
  if (service.pricingUnit === "PER_ORDER") return 1;
  if (service.pricingUnit === "PER_GUEST") {
    return Math.min(Math.max(1, requested || guestCount || 1), Math.max(1, guestCount));
  }
  return Math.min(Math.max(1, requested || 1), 99);
};

export const calculateAddOnLineTotal = (
  service: AddOnServiceItem,
  selection: AddOnSelection,
  guestCount: number,
  nights: number,
) => {
  const quantity = normalizeSelectionQuantity(service, selection.quantity, guestCount);
  const packageCycleUnit =
    service.pricingUnit === "PER_PACKAGE_CYCLE" || service.pricingUnit === "PER_NIGHT";
  const multiplier = packageCycleUnit ? Math.max(1, nights) : 1;
  return service.price * quantity * multiplier;
};

export const pricingUnitLabel = (
  unit: AddOnPricingUnit,
  localize: (vi?: string | null, en?: string | null) => string,
) => ({
  PER_GUEST: localize("/ người", "/ guest"),
  PER_PACKAGE_CYCLE: localize("/ mục / chu kỳ lưu trú", "/ item / stay cycle"),
  PER_NIGHT: localize("/ mục / chu kỳ lưu trú", "/ item / stay cycle"),
  PER_ITEM: localize("/ món", "/ item"),
  PER_ORDER: localize("/ đơn", "/ order"),
  PER_USE: localize("/ lần", "/ use"),
}[unit]);

export const serviceStatusLabel = (
  status: ReservationServiceStatus,
  localize: (vi?: string | null, en?: string | null) => string,
) => ({
  REQUESTED: localize("Chờ xác nhận", "Requested"),
  CONFIRMED: localize("Đã xác nhận", "Confirmed"),
  FULFILLED: localize("Đã phục vụ", "Fulfilled"),
  CANCELLED: localize("Đã hủy", "Cancelled"),
}[status]);
