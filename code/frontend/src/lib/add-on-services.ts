import { publicApiClient } from "@/lib/api";

export type AddOnServiceFlow = "BOOKING_TIME" | "IN_STAY";
export type AddOnPricingUnit = "PER_GUEST" | "PER_NIGHT" | "PER_ITEM" | "PER_ORDER" | "PER_USE";
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

export const chargeableNights = (checkIn?: string, checkOut?: string) => {
  if (!checkIn || !checkOut) return 1;
  const start = new Date(checkIn);
  const end = new Date(checkOut);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end <= start) return 1;
  return Math.max(1, Math.ceil((end.getTime() - start.getTime()) / 86_400_000));
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
  const multiplier = service.pricingUnit === "PER_NIGHT" ? Math.max(1, nights) : 1;
  return service.price * quantity * multiplier;
};

export const pricingUnitLabel = (
  unit: AddOnPricingUnit,
  localize: (vi?: string | null, en?: string | null) => string,
) => ({
  PER_GUEST: localize("/ người", "/ guest"),
  PER_NIGHT: localize("/ món / đêm", "/ item / night"),
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
