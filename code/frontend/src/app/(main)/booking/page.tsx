"use client";

import React, { useState, useEffect, useRef, Suspense } from "react";
import axios from "axios";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import ViewportModal from "@/components/UI/ViewportModal";
import { useSearchParams, useRouter } from "next/navigation";
import Link from "next/link";
import { apiClient, authSession, publicApiClient } from "@/lib/api";
import { saveGuestReservationToken } from "@/lib/guest-reservation-token";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getPublicRoomTypes } from "@/lib/public-catalog";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";
import {
  allocateGuestsToRoomTypes,
  calculateSelectedGuestCapacity,
  normalizeGuestCapacity,
} from "@/lib/guest-capacity";
import BookingAddOnSelector from "@/components/add-on-services/BookingAddOnSelector";
import {
  type AddOnSelection,
  type AddOnServiceItem,
  calculateAddOnLineTotal,
  chargeableNights,
  getAddOnCatalog,
} from "@/lib/add-on-services";
import { isStayWithinMaximum } from "@/lib/stay-window";

interface BookingData {
  roomName: string;
  size: string;
  image: string;
  totalHours: number;
  checkInDate: string;
  checkOutDate: string;
  adultsCount: string;
  childrenCount: string;
  selectedRooms: Array<{
    roomTypeId: number;
    roomName: string;
    quantity: number;
    includedGuestsPerRoom: number;
    maxGuestsPerRoom: number;
    extraGuestPrice: number;
  }>;
}

interface CurrentUserProfile {
  fullName?: string;
  username?: string;
  email?: string;
  phone?: string;
  address?: string;
  type?: string;
}

interface PendingReservationSession {
  id: number;
  reservationCode: string;
  guestToken?: string;
  guest: boolean;
}

interface PricingQuoteLine {
  roomTypeId: number;
  roomTypeName: string;
  quantity: number;
  lineGuestCount: number;
  includedGuestsPerRoom: number;
  maxGuestsPerRoom: number;
  extraGuestPrice: number;
  appliedPackage: "HOURLY" | "OVERNIGHT" | "DAILY";
  roomCharge: number;
  extraGuestCount: number;
  extraGuestCharge: number;
}

interface PricingQuoteServiceLine {
  serviceId: number;
  serviceCode: string;
  serviceName: string;
  pricingUnit: string;
  unitPrice: number;
  quantity: number;
  multiplier: number;
  billableQuantity: number;
  totalPrice: number;
}

interface PricingQuote {
  quoteId: string;
  quoteExpiresAtUtc: string;
  quoteHash: string;
  pricingAlgorithmVersion: "MOTEL_PACKAGE_V2";
  displayPackageSummary: "HOURLY" | "OVERNIGHT" | "DAILY";
  inventoryProtectedUntil: string;
  roomCharge: number;
  extraGuestCharge: number;
  serviceCharge: number;
  totalAmount: number;
  lines: PricingQuoteLine[];
  services: PricingQuoteServiceLine[];
}

interface PricingQuoteRequestPayload {
  checkIn: string;
  checkOut: string;
  guestCount: number;
  rooms: Array<{
    roomTypeId: number;
    quantity: number;
    lineGuestCount: number;
  }>;
  services: Array<{
    serviceId: number;
    quantity: number;
    notes?: string;
  }>;
}

type PricingQuoteMode = "checking" | "v2" | "error";

interface BookingRoomType {
  id: number;
  typeName?: string;
  typeNameEn?: string;
  includedGuests?: number;
  maxGuests?: number;
  extraGuestPrice?: number;
  imageUrl?: string;
  size?: string;
}

const getApiErrorMessage = (error: unknown, fallback: string) =>
  axios.isAxiosError<{ message?: string }>(error)
    ? error.response?.data?.message || fallback
    : error instanceof Error && error.message
      ? error.message
      : fallback;

const getApiErrorCode = (error: unknown) =>
  axios.isAxiosError<{ code?: number }>(error)
    ? Number(error.response?.data?.code || 0)
    : 0;

const toBackendLocalDateTime = (value: string) =>
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) ? `${value}:00` : value;

const normalizePricingQuote = (value: PricingQuote): PricingQuote => ({
  ...value,
  roomCharge: Number(value.roomCharge || 0),
  extraGuestCharge: Number(value.extraGuestCharge || 0),
  serviceCharge: Number(value.serviceCharge || 0),
  totalAmount: Number(value.totalAmount || 0),
  lines: Array.isArray(value.lines)
    ? value.lines.map((line) => ({
        ...line,
        roomCharge: Number(line.roomCharge || 0),
        extraGuestCharge: Number(line.extraGuestCharge || 0),
      }))
    : [],
  services: Array.isArray(value.services)
    ? value.services.map((line) => ({
        ...line,
        serviceId: Number(line.serviceId),
        unitPrice: Number(line.unitPrice || 0),
        quantity: Number(line.quantity || 0),
        multiplier: Number(line.multiplier || 0),
        billableQuantity: Number(line.billableQuantity || 0),
        totalPrice: Number(line.totalPrice || 0),
      }))
    : [],
});

const requestPricingQuote = async (
  payload: PricingQuoteRequestPayload,
  signal?: AbortSignal,
) => {
  const response = await publicApiClient.post(
    "/api/pricing/quote",
    payload,
    { signal },
  );
  const quote = (response.data?.data ?? response.data) as PricingQuote;
  if (!quote?.quoteId || !quote?.quoteHash || !quote?.quoteExpiresAtUtc) {
    throw new Error("Backend không trả về báo giá hợp lệ");
  }
  return normalizePricingQuote(quote);
};

// Fallback details removed as we use API

function BookingFormContent() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const { localeTag, localize } = useLanguage();

  const roomId = searchParams.get("roomId");
  const roomTypesParam = searchParams.get("roomTypes");
  const checkIn = searchParams.get("checkIn");
  const checkOut = searchParams.get("checkOut");
  const adults = searchParams.get("adults") || "2";
  const childrenVal = searchParams.get("children") || "0";

  const [step] = useState(2); // Step 2 is active on this page
  const [bookingData, setBookingData] = useState<BookingData | null>(null);

  // Form states
  const [fullName, setFullName] = useState("");
  const [email, setEmail] = useState("");
  const [phone, setPhone] = useState("");
  const [country, setCountry] = useState("Việt Nam");
  const [specialRequest, setSpecialRequest] = useState("");
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [profileFieldLocks, setProfileFieldLocks] = useState({
    fullName: false,
    email: false,
    phone: false,
    country: false,
  });
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [addOnCatalog, setAddOnCatalog] = useState<AddOnServiceItem[]>([]);
  const [addOnSelections, setAddOnSelections] = useState<Record<number, AddOnSelection>>({});
  const [isAddOnLoading, setIsAddOnLoading] = useState(true);
  const [addOnLoadError, setAddOnLoadError] = useState("");
  const [isAddOnModalOpen, setIsAddOnModalOpen] = useState(false);
  const [lineGuestCounts, setLineGuestCounts] = useState<Record<number, number>>({});
  const autoAllocationKeyRef = useRef("");
  const termsSectionRef = useRef<HTMLDivElement>(null);
  const termsCheckboxRef = useRef<HTMLInputElement>(null);
  const [pricingQuote, setPricingQuote] = useState<PricingQuote | null>(null);
  const [pricingQuoteMode, setPricingQuoteMode] = useState<PricingQuoteMode>("checking");
  const [pricingQuoteError, setPricingQuoteError] = useState("");
  const [quoteRefreshNonce, setQuoteRefreshNonce] = useState(0);

  // Payment states
  const [agree, setAgree] = useState(false);
  const [paymentPlan, setPaymentPlan] = useState<"DEPOSIT_50" | "PREPAY_100">("DEPOSIT_50");
  const [paymentError, setPaymentError] = useState("");
  const [pendingReservation, setPendingReservation] = useState<PendingReservationSession | null>(null);
  const [isConfirmationOpen, setIsConfirmationOpen] = useState(false);

  // Booking hold and code states (US3.2)
  const [bookingCode, setBookingCode] = useState("");
  const [timeLeft, setTimeLeft] = useState(300); // QR giữ phòng tối đa 5 phút

  useEffect(() => {
    // Catalog data supplies presentation and capacity only. The authoritative
    // monetary amount always comes from the versioned pricing quote endpoint.
    const requestedRooms = roomTypesParam
      ? roomTypesParam.split(",").map((item) => {
          const [id, quantity] = item.split(":").map(Number);
          return { id, quantity };
        }).filter((item) => item.id > 0 && item.quantity > 0)
      : roomId ? [{ id: Number(roomId), quantity: 1 }] : [];

    if (requestedRooms.length === 0 || !checkIn || !checkOut) {
      router.push("/rooms");
      return;
    }

    const checkInTime = new Date(checkIn);
    const checkOutTime = new Date(checkOut);
    if (
      Number.isNaN(checkInTime.getTime())
      || Number.isNaN(checkOutTime.getTime())
      || checkOutTime <= checkInTime
      || !isStayWithinMaximum(checkInTime, checkOutTime)
    ) {
      router.push("/reservation");
      return;
    }
    const totalHours = Math.max(
      1,
      Math.ceil((checkOutTime.getTime() - checkInTime.getTime()) / (1000 * 60 * 60)),
    );

    // Một catalog dùng chung thay cho một request riêng cho từng loại phòng.
    getPublicRoomTypes<BookingRoomType>()
      .then((roomTypes) => {
        const matches = requestedRooms.map((requested) => ({
          requested,
          roomType: roomTypes.find((item) => Number(item.id) === requested.id),
        }));
        if (matches.some((item) => !item.roomType)) {
          throw new Error("Không tìm thấy loại phòng đã chọn");
        }
        const selectedRooms = matches.map(({ requested, roomType }) => ({
          roomTypeId: requested.id,
          roomName: localize(roomType?.typeName, roomType?.typeNameEn),
          quantity: requested.quantity,
          includedGuestsPerRoom: normalizeGuestCapacity(
            roomType?.includedGuests,
            normalizeGuestCapacity(roomType?.maxGuests),
          ),
          maxGuestsPerRoom: normalizeGuestCapacity(roomType?.maxGuests),
          extraGuestPrice: Number(roomType?.extraGuestPrice || 0),
        }));
        if (selectedRooms.length > 0) {
          const totalGuests = Number(adults || 0) + Number(childrenVal || 0);
          const autoAllocationKey = JSON.stringify({
            rooms: selectedRooms.map((room) => ({
              roomTypeId: room.roomTypeId,
              quantity: room.quantity,
              includedGuestsPerRoom: room.includedGuestsPerRoom,
              maxGuestsPerRoom: room.maxGuestsPerRoom,
              extraGuestPrice: room.extraGuestPrice,
            })),
            totalGuests,
          });
          // Recompute only when the actual selection, capacity, surcharge or
          // declared party changes. Locale/presentation refreshes must not
          // overwrite a distribution the customer has chosen manually.
          if (autoAllocationKeyRef.current !== autoAllocationKey) {
            setLineGuestCounts(allocateGuestsToRoomTypes(selectedRooms, totalGuests));
            autoAllocationKeyRef.current = autoAllocationKey;
          }
          const match = matches[0].roomType as BookingRoomType;
          setBookingData({
            roomName: selectedRooms.map((room) => `${room.quantity} × ${room.roomName}`).join(", "),
            size: match.size || "",
            image: match.imageUrl || "",
            totalHours,
            checkInDate: checkIn,
            checkOutDate: checkOut,
            adultsCount: adults,
            childrenCount: childrenVal,
            selectedRooms,
          });
        }
      })
      .catch(err => {
        console.error("Error fetching room for booking", err);
        router.push("/rooms");
      });
  }, [roomId, roomTypesParam, checkIn, checkOut, adults, childrenVal, router, localize]);

  useEffect(() => {
    let active = true;

    const applyProfile = (profile: CurrentUserProfile, lockVerifiedFields = false) => {
      if (!active) return;
      const profileName = profile.fullName?.trim() || profile.username?.trim() || "";
      const profileEmail = profile.email?.trim() || "";
      const profilePhone = profile.phone?.trim() || "";
      if (profileName) setFullName(profileName);
      if (profileEmail) setEmail(profileEmail);
      if (profilePhone) setPhone(profilePhone);
      const normalizedAddress = profile.address?.trim().toLocaleLowerCase("vi-VN");
      const recognizedCountry = normalizedAddress === "việt nam" || normalizedAddress === "viet nam" || normalizedAddress === "vietnam"
        ? "Việt Nam"
        : normalizedAddress === "mỹ" || normalizedAddress === "usa" || normalizedAddress === "united states"
          ? "Mỹ"
          : normalizedAddress === "nhật bản" || normalizedAddress === "japan"
            ? "Nhật Bản"
            : normalizedAddress === "hàn quốc" || normalizedAddress === "south korea" || normalizedAddress === "korea"
              ? "Hàn Quốc"
              : "";
      if (recognizedCountry) setCountry(recognizedCountry);
      if (lockVerifiedFields) {
        setProfileFieldLocks({
          fullName: Boolean(profileName),
          email: Boolean(profileEmail),
          phone: Boolean(profilePhone),
          country: Boolean(recognizedCountry),
        });
      }
    };

    // Điền ngay dữ liệu phiên đã cache để form không nháy trắng, sau đó luôn
    // đồng bộ lại từ /api/user/me. Không lọc role: CUSTOMER, STAFF và ADMIN
    // dùng cùng một contract hồ sơ khi tự đặt phòng trên trang public.
    try {
      const cachedProfile = window.localStorage.getItem("user");
      if (cachedProfile) applyProfile(JSON.parse(cachedProfile) as CurrentUserProfile);
    } catch {
      // Cache chỉ là tối ưu hiển thị; backend vẫn là nguồn xác thực cuối cùng.
    }

    void authSession.getCurrentUser<CurrentUserProfile>(false).then((profile) => {
      if (!active) return;
      setIsAuthenticated(Boolean(profile));
      if (profile) applyProfile(profile, true);
      else setProfileFieldLocks({ fullName: false, email: false, phone: false, country: false });
    });

    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    setIsAddOnLoading(true);
    getAddOnCatalog("BOOKING_TIME")
      .then((items) => {
        if (active) setAddOnCatalog(items);
      })
      .catch(() => {
        if (active) setAddOnLoadError(localize("Không thể tải dịch vụ đặt trước. Bạn vẫn có thể tiếp tục chỉ với phòng.", "Could not load pre-bookable services. You can still continue with rooms only."));
      })
      .finally(() => {
        if (active) setIsAddOnLoading(false);
      });
    return () => {
      active = false;
    };
  }, [localize]);

  const quoteGuestCount = bookingData
    ? Number(bookingData.adultsCount || 0) + Number(bookingData.childrenCount || 0)
    : 0;
  const quotedLineGuestTotal = Object.values(lineGuestCounts)
    .reduce((sum, value) => sum + Number(value || 0), 0);
  const quoteServices = addOnCatalog
    .filter((service) => Boolean(addOnSelections[service.id]))
    .map((service) => ({
      serviceId: service.id,
      quantity: addOnSelections[service.id].quantity,
    }));
  const quoteRequestPayload: PricingQuoteRequestPayload | null = bookingData
    && quoteGuestCount >= 1
    && quotedLineGuestTotal === quoteGuestCount
    && bookingData.selectedRooms.every((room) => {
      const allocated = lineGuestCounts[room.roomTypeId];
      return Number.isInteger(allocated)
        && allocated >= room.quantity
        && allocated <= room.quantity * room.maxGuestsPerRoom;
    })
    ? {
        checkIn: toBackendLocalDateTime(bookingData.checkInDate),
        checkOut: toBackendLocalDateTime(bookingData.checkOutDate),
        guestCount: quoteGuestCount,
        rooms: bookingData.selectedRooms.map((room) => ({
          roomTypeId: room.roomTypeId,
          quantity: room.quantity,
          lineGuestCount: lineGuestCounts[room.roomTypeId],
        })),
        services: quoteServices,
      }
    : null;
  const quoteRequestKey = quoteRequestPayload
    ? JSON.stringify(quoteRequestPayload)
    : "";

  useEffect(() => {
    if (!bookingData || pendingReservation || isAddOnLoading) return;
    if (!quoteRequestPayload) {
      setPricingQuote(null);
      setPricingQuoteMode("error");
      setPricingQuoteError(localize(
        "Hãy phân bổ đủ số khách cho từng hạng phòng trước khi kiểm tra giá.",
        "Allocate every guest to a selected room type before checking the price.",
      ));
      return;
    }

    let active = true;
    const abortController = new AbortController();
    setPricingQuote(null);
    setPricingQuoteMode("checking");
    setPricingQuoteError("");
    const timer = window.setTimeout(() => {
      void requestPricingQuote(
        quoteRequestPayload,
        abortController.signal,
      )
        .then((quote) => {
          if (!active) return;
          setPricingQuote(quote);
          setPricingQuoteMode("v2");
        })
        .catch((error: unknown) => {
          if (!active) return;
          setPricingQuote(null);
          setPricingQuoteMode("error");
          setPricingQuoteError(getApiErrorMessage(
            error,
            localize(
              "Chưa thể kiểm tra giá chính xác. Vui lòng thử lại.",
              "The authoritative price could not be checked. Please try again.",
            ),
          ));
        });
    }, 350);

    return () => {
      active = false;
      abortController.abort();
      window.clearTimeout(timer);
    };
  // quoteRequestKey is the canonical dependency for nested room/service selections.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [bookingData, isAddOnLoading, pendingReservation, quoteRequestKey, quoteRefreshNonce, localize]);

  // Hold Countdown effect
  useEffect(() => {
    if (step !== 3 || timeLeft <= 0) return;
    const interval = setInterval(() => {
      setTimeLeft((prev) => prev - 1);
    }, 1000);
    return () => clearInterval(interval);
  }, [step, timeLeft]);

  if (!bookingData) return null;

  // Math calculations
  const stayNights = chargeableNights(bookingData.checkInDate, bookingData.checkOutDate);
  const declaredGuestCount = Number(bookingData.adultsCount || 0) + Number(bookingData.childrenCount || 0);
  const hasAuthoritativeQuote = pricingQuoteMode === "v2" && pricingQuote != null;
  const quotedServicesById = new Map(
    (hasAuthoritativeQuote ? pricingQuote.services : [])
      .map((line) => [line.serviceId, line] as const),
  );
  const selectedAddOns = addOnCatalog
    .filter((service) => Boolean(addOnSelections[service.id]))
    .map((service) => {
      const quotedService = quotedServicesById.get(service.id);
      return {
        service,
        selection: addOnSelections[service.id],
        total: quotedService?.totalPrice ?? calculateAddOnLineTotal(
          service,
          addOnSelections[service.id],
          declaredGuestCount,
          stayNights,
        ),
      };
    });
  const authoritativeServiceTotals = Object.fromEntries(
    [...quotedServicesById.values()].map((line) => [
      line.serviceId,
      line.totalPrice,
    ]),
  );
  const roomTotal = hasAuthoritativeQuote ? pricingQuote.roomCharge : 0;
  const extraGuestTotal = hasAuthoritativeQuote ? pricingQuote.extraGuestCharge : 0;
  const displayedAddOnTotal = hasAuthoritativeQuote ? pricingQuote.serviceCharge : 0;
  const addOnSummaryTotal = hasAuthoritativeQuote
    ? displayedAddOnTotal
    : selectedAddOns.reduce((sum, item) => sum + item.total, 0);
  const total = hasAuthoritativeQuote ? pricingQuote.totalAmount : 0;
  const deposit50 = Math.ceil(total * 0.5);
  const amountDueNow = paymentPlan === "PREPAY_100" ? total : deposit50;
  const selectedRoomCount = bookingData.selectedRooms.reduce((sum, room) => sum + room.quantity, 0);
  const selectedGuestCapacity = calculateSelectedGuestCapacity(bookingData.selectedRooms);
  const displayedPackageLabel = pricingQuote?.displayPackageSummary === "OVERNIGHT"
    ? localize("Qua đêm", "Overnight")
    : pricingQuote?.displayPackageSummary === "DAILY"
      ? localize("Ngày đêm", "Daily")
      : localize("Nghỉ giờ", "Hourly");
  const checkInHourMatch = bookingData.checkInDate.match(
    /(?:T|\s)(\d{2}):(\d{2})/,
  );
  const isEarlyMorningOvernight =
    pricingQuote?.displayPackageSummary === "OVERNIGHT"
    && checkInHourMatch != null
    && Number(checkInHourMatch[1]) < 5;
  const isBookingActionDisabled = isSubmitting || (
    !pendingReservation
    && (
      !quoteRequestPayload
      || pricingQuoteMode === "checking"
      || pricingQuoteMode === "error"
    )
  );

  const formatVND = (num: number) => {
    return num.toLocaleString("vi-VN") + " đ";
  };

  const formatDateTimeVietnamese = (dateStr: string) => {
    const date = new Date(dateStr);
    return Number.isNaN(date.getTime())
      ? dateStr
      : date.toLocaleString(localeTag, {
          weekday: "short",
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
          hour: "2-digit",
          minute: "2-digit",
        });
  };

  const formatTimeLeft = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
  };

  const getFormValidationError = (name = fullName, customerEmail = email, customerPhone = phone) => {
    const normalizedPhone = customerPhone.replace(/[\s().+-]/g, "");
    if (selectedGuestCapacity < declaredGuestCount) {
      return localize(
        `Các phòng đã chọn chỉ chứa tối đa ${selectedGuestCapacity} khách, thấp hơn ${declaredGuestCount} khách của đơn. Vui lòng quay lại chọn thêm phòng.`,
        `The selected rooms allow ${selectedGuestCapacity} guests, below the ${declaredGuestCount} guests in this reservation. Please go back and add rooms.`,
      );
    }
    if (!pendingReservation && quotedLineGuestTotal !== declaredGuestCount) {
      return localize(
        `Đã phân bổ ${quotedLineGuestTotal}/${declaredGuestCount} khách. Vui lòng phân bổ đủ khách theo từng hạng phòng.`,
        `${quotedLineGuestTotal}/${declaredGuestCount} guests are allocated. Allocate every guest to a room type.`,
      );
    }
    if (!pendingReservation && pricingQuoteMode === "checking") {
      return localize(
        "Hệ thống đang kiểm tra giá chính xác, vui lòng chờ trong giây lát.",
        "The authoritative price is being checked. Please wait a moment.",
      );
    }
    if (!pendingReservation && pricingQuoteMode === "error") {
      return pricingQuoteError || localize(
        "Chưa thể kiểm tra giá chính xác. Vui lòng thử lại.",
        "The authoritative price could not be checked. Please try again.",
      );
    }
    if (!pendingReservation && (!pricingQuote || pricingQuoteMode !== "v2")) {
      return localize(
        "Chưa có báo giá hợp lệ cho kỳ lưu trú này. Vui lòng thử kiểm tra lại.",
        "There is no valid quote for this stay. Please retry the price check.",
      );
    }
    if (name.trim().length < 2 || name.trim().length > 100) {
      return localize("Họ và tên phải từ 2–100 ký tự.", "Full name must contain 2–100 characters.");
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(customerEmail.trim()) || customerEmail.trim().length > 254) {
      return localize("Vui lòng nhập địa chỉ email hợp lệ.", "Please enter a valid email address.");
    }
    if (!/^\d{8,15}$/.test(normalizedPhone)) {
      return localize("Số điện thoại phải gồm 8–15 chữ số.", "Phone number must contain 8–15 digits.");
    }
    if (specialRequest.trim().length > 500) {
      return localize("Yêu cầu đặc biệt không được vượt quá 500 ký tự.", "Special requests cannot exceed 500 characters.");
    }
    if (!agree) {
      return localize("Bạn phải đồng ý với Điều khoản & Điều kiện trước khi đặt phòng.", "You must accept the Terms & Conditions before booking.");
    }
    return "";
  };

  const handleRequestBooking = (event: React.MouseEvent<HTMLButtonElement>) => {
    event.preventDefault();
    if (!agree) {
      setPaymentError(localize(
        "Bạn phải đồng ý với Điều khoản & Điều kiện trước khi đặt phòng.",
        "You must accept the Terms & Conditions before booking.",
      ));
      termsSectionRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
      window.requestAnimationFrame(() => termsCheckboxRef.current?.focus({ preventScroll: true }));
      return;
    }
    const validationError = getFormValidationError();
    setPaymentError(validationError);
    if (validationError) return;
    setIsConfirmationOpen(true);
  };

  const handleConfirmBooking = async () => {
    setPaymentError("");
    // Kiểm tra trạng thái thật từ backend; không dựa vào state có thể
    // chưa kịp cập nhật sau khi trang vừa khôi phục refresh cookie.
    const currentUser = await authSession.getCurrentUser<CurrentUserProfile>(false);
    const isGuestBooking = !currentUser;
    const bookingFullName = currentUser?.fullName || fullName;
    const bookingEmail = currentUser?.email || email;
    const bookingPhone = currentUser?.phone || phone;

    setIsAuthenticated(!isGuestBooking);
    const validationError = getFormValidationError(bookingFullName, bookingEmail, bookingPhone);
    if (validationError) {
      setPaymentError(validationError);
      setIsConfirmationOpen(false);
      return;
    }

    if (!pendingReservation && pricingQuoteMode === "v2") {
      const quoteExpiry = pricingQuote
        ? new Date(pricingQuote.quoteExpiresAtUtc).getTime()
        : Number.NaN;
      if (!pricingQuote || Number.isNaN(quoteExpiry) || quoteExpiry <= Date.now() + 5_000) {
        setIsConfirmationOpen(false);
        if (!quoteRequestPayload) {
          setPaymentError(localize(
            "Thông tin phân bổ khách chưa hợp lệ để làm mới báo giá.",
            "The guest allocation is not valid for refreshing the quote.",
          ));
          return;
        }
        setIsSubmitting(true);
        try {
          const refreshedQuote = await requestPricingQuote(quoteRequestPayload);
          setPricingQuote(refreshedQuote);
          setPricingQuoteMode("v2");
          setPricingQuoteError("");
          setPaymentError(localize(
            "Báo giá vừa được làm mới. Vui lòng kiểm tra số tiền và xác nhận lại.",
            "The quote was refreshed. Review the amount and confirm again.",
          ));
        } catch (error: unknown) {
          setPricingQuote(null);
          setPricingQuoteMode("error");
          const message = getApiErrorMessage(
            error,
            localize("Không thể làm mới báo giá.", "The quote could not be refreshed."),
          );
          setPricingQuoteError(message);
          setPaymentError(message);
        } finally {
          setIsSubmitting(false);
        }
        return;
      }
    }

    setIsConfirmationOpen(false);
    setIsSubmitting(true);
    let reservationCreateScope: string | null = null;
    try {
      // Tạo PAYMENT_PENDING trước; backend chỉ khóa tồn phòng ở bước
      // /payments/create, ngay khi mã QR thực sự được phát hành.
      const checkInDateTime = toBackendLocalDateTime(bookingData.checkInDate);
      const checkOutDateTime = toBackendLocalDateTime(bookingData.checkOutDate);
      let reservation = pendingReservation;
      if (!reservation) {
        const reservationClient = isGuestBooking ? publicApiClient : apiClient;
        if (pricingQuoteMode !== "v2" || !pricingQuote) {
          throw new Error(localize(
            "Báo giá không còn hợp lệ. Vui lòng kiểm tra lại trước khi đặt phòng.",
            "The quote is no longer valid. Refresh it before booking.",
          ));
        }
        const activeQuote = pricingQuote;
        reservationCreateScope = `reservation:create:booking:${activeQuote.quoteId}`;
        const createResResponse = await reservationClient.post("/api/reservations", {
          checkIn: checkInDateTime,
          checkOut: checkOutDateTime,
          guestCount: declaredGuestCount,
          note: specialRequest,
          paymentPlan,
          customer: {
            fullName: bookingFullName,
            email: bookingEmail,
            phone: bookingPhone,
            address: country,
          },
          roomTypes: bookingData.selectedRooms.map((room) => ({
            roomTypeId: room.roomTypeId,
            quantity: room.quantity,
            lineGuestCount: lineGuestCounts[room.roomTypeId],
          })),
          services: selectedAddOns.map(({ service, selection }) => ({
            serviceId: service.id,
            quantity: selection.quantity,
            notes: selection.notes.trim() || undefined,
          })),
          quoteId: activeQuote.quoteId,
          quoteHash: activeQuote.quoteHash,
        }, {
          headers: {
            "Idempotency-Key": getOrCreateIdempotencyKey(reservationCreateScope),
          },
        });

        const created = createResResponse.data?.data ?? createResResponse.data;
        if (typeof created?.id !== "number" || !created?.reservationCode) {
          throw new Error("Không thể tạo đơn đặt phòng");
        }
        if (isGuestBooking && !created.guestToken) {
          throw new Error("Backend không trả về guestToken cho đặt phòng không đăng nhập");
        }
        reservation = {
          id: created.id,
          reservationCode: created.reservationCode,
          guestToken: created.guestToken,
          guest: isGuestBooking,
        };
        setPendingReservation(reservation);
        clearIdempotencyKey(reservationCreateScope);
        if (reservation.guestToken) {
          saveGuestReservationToken(reservation.id, reservation.guestToken);
        }
      }

      const code = reservation.reservationCode;
      const guestToken = reservation.guestToken;

      setBookingCode(code);
      setTimeLeft(300);

      // 2. Process Payment
      const paymentClient = reservation.guest ? publicApiClient : apiClient;
      const idempotencyKey = getOrCreateIdempotencyKey(
        `payment:${reservation.id}:DEPOSIT`,
      );
      const paymentResponse = await paymentClient.post("/api/payments/create", {
        bookingId: reservation.id,
        provider: "SEPAY",
        purpose: "DEPOSIT",
        orderInfo: `Thanh toan dat phong ${code}`
      }, {
        headers: {
          "Idempotency-Key": idempotencyKey,
          ...(reservation.guest ? { "X-Guest-Token": guestToken } : {}),
        },
      });
      const payment = paymentResponse.data?.data ?? paymentResponse.data;
      const paymentUrl = typeof payment?.paymentUrl === "string" ? payment.paymentUrl.trim() : "";
      const transactionId = typeof payment?.transactionId === "string" ? payment.transactionId.trim() : "";
      const paymentResultUrl = paymentUrl || (transactionId
        ? `/booking/payment-result?transactionId=${encodeURIComponent(transactionId)}`
        : "");

      if (!paymentResultUrl) {
        throw new Error("Backend không trả về đường dẫn hoặc mã giao dịch QR hợp lệ");
      }
      window.location.assign(paymentResultUrl);
    } catch (error: unknown) {
      console.error("Booking error:", error);
      const errorCode = getApiErrorCode(error);
      if (
        !pendingReservation
        && (errorCode === 5082 || errorCode === 5083)
        && quoteRequestPayload
      ) {
        if (reservationCreateScope) clearIdempotencyKey(reservationCreateScope);
        try {
          const previousQuote = pricingQuote;
          const refreshedQuote = await requestPricingQuote(quoteRequestPayload);
          const amountChanged = previousQuote != null
            && Number(previousQuote.totalAmount) !== Number(refreshedQuote.totalAmount);
          setPricingQuote(refreshedQuote);
          setPricingQuoteMode("v2");
          setPricingQuoteError("");
          setPaymentError(amountChanged
            ? localize(
                `Tổng tiền đã đổi từ ${formatVND(Number(previousQuote?.totalAmount || 0))} thành ${formatVND(refreshedQuote.totalAmount)}. Vui lòng kiểm tra và xác nhận lại.`,
                `The total changed from ${formatVND(Number(previousQuote?.totalAmount || 0))} to ${formatVND(refreshedQuote.totalAmount)}. Review and confirm again.`,
              )
            : localize(
                "Báo giá hoặc phiên bản chính sách đã được làm mới; tổng tiền không đổi. Vui lòng xác nhận lại.",
                "The quote or policy version was refreshed; the total is unchanged. Confirm again.",
              ));
        } catch (refreshError: unknown) {
          const refreshMessage = getApiErrorMessage(
            refreshError,
            localize("Không thể cập nhật lại báo giá.", "The quote could not be refreshed."),
          );
          setPricingQuote(null);
          setPricingQuoteMode("error");
          setPricingQuoteError(refreshMessage);
          setPaymentError(refreshMessage);
        }
        return;
      }
      const message = getApiErrorMessage(
        error,
        "Lỗi trong quá trình tạo thanh toán QR. Vui lòng kiểm tra thông tin và thử lại."
      );
      if (/hết hạn|đã hủy|không còn hiệu lực/i.test(message)) {
        setPendingReservation(null);
      }
      setPaymentError(message);
    } finally {
      setIsSubmitting(false);
    }
  };

  if (step === 3) {
    return (
      <div className="bg-[#F1F0EA] py-10 min-h-screen">
        <div className="max-w-6xl mx-auto px-6 space-y-8">
          {/* Step Indicator Header */}
          <div className="flex items-center justify-center gap-6 text-xs sm:text-sm font-semibold text-text-light border-b border-gray-200/50 pb-6">
            <div className="flex items-center gap-2 text-[#80632F] font-medium">
              <span className="w-6 h-6 rounded-full bg-[#80632F] text-white flex items-center justify-center text-[10px] font-bold">1</span>
              <span className="uppercase tracking-wider">Chọn phòng</span>
            </div>
            <div className="h-px w-10 sm:w-16 bg-[#80632F]" />
            <div className="flex items-center gap-2 text-[#80632F] font-medium">
              <span className="w-6 h-6 rounded-full bg-[#80632F] text-white flex items-center justify-center text-[10px] font-bold">2</span>
              <span className="uppercase tracking-wider">Dịch vụ</span>
            </div>
            <div className="h-px w-10 sm:w-16 bg-[#80632F]" />
            <div className="flex items-center gap-2 text-primary-navy font-bold">
              <span className="w-6 h-6 rounded-full bg-primary-navy text-white flex items-center justify-center text-[10px] font-bold">3</span>
              <span className="uppercase tracking-wider">Xác nhận</span>
            </div>
          </div>

          {/* Success Checkmark & Titles */}
          <div className="text-center space-y-4 max-w-2xl mx-auto pb-6">
            <div className="w-16 h-12 bg-[#80632F] rounded-md flex items-center justify-center mx-auto text-white text-xl font-bold shadow-sm">
              ✓
            </div>
            <h2 className="font-serif text-3xl md:text-4xl font-bold text-primary-navy tracking-wide">
              Đặt phòng thành công!
            </h2>
            <p className="text-sm md:text-base font-serif font-bold text-[#80632F] tracking-widest uppercase">
              Mã số đặt phòng: {bookingCode}
            </p>
            {timeLeft > 0 ? (
              <div className="inline-block bg-[#F0EADF] border border-[#F0EADF] text-[#80632F] px-4 py-2 rounded-xl text-xs font-bold mt-2 animate-pulse">
                ⏰ Đã khóa giữ phòng! Vui lòng quét mã QR và thanh toán trong: <span className="text-sm font-mono text-red-600">{formatTimeLeft(timeLeft)}</span>
              </div>
            ) : (
              <div className="inline-block bg-red-50 border border-red-100 text-red-600 px-4 py-2 rounded-xl text-xs font-bold mt-2">
                ⚠️ Hết thời gian giữ phòng tạm thời. Đơn đặt phòng của bạn đã bị hủy tự động!
              </div>
            )}
            <p className="text-xs text-text-light font-medium pt-2">
              Một email xác nhận đã được gửi đến địa chỉ email của bạn.
            </p>
          </div>

          {/* Two Columns Grid */}
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-8 items-start">
            
            {/* Left Column: Confirmation Details */}
            <div className="lg:col-span-2 space-y-6">
              <div className="bg-white border border-gray-200 rounded-sm shadow-sm overflow-hidden">
                <div className="p-6 border-b border-gray-100 bg-gray-50/50">
                  <h3 className="text-sm font-bold text-primary-navy tracking-wider uppercase">
                    Chi tiết xác nhận
                  </h3>
                </div>

                <div className="p-8 grid grid-cols-1 md:grid-cols-2 gap-8 text-sm">
                  {/* Customer Info */}
                  <div className="space-y-4">
                    <h4 className="text-[10px] font-bold text-[#80632F] tracking-widest uppercase">
                      Thông tin khách hàng
                    </h4>
                    <div className="space-y-3">
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Họ tên</p>
                        <p className="font-semibold text-text-dark text-base mt-0.5">{fullName || "Not provided"}</p>
                      </div>
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Email</p>
                        <p className="font-medium text-text-dark mt-0.5">{email || "Not provided"}</p>
                      </div>
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Số điện thoại</p>
                        <p className="font-medium text-text-dark mt-0.5">{phone || "Not provided"}</p>
                      </div>
                    </div>
                  </div>

                  {/* Room Booking Info */}
                  <div className="space-y-4">
                    <h4 className="text-[10px] font-bold text-[#80632F] tracking-widest uppercase">
                      Thông tin đặt phòng
                    </h4>
                    <div className="space-y-3">
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Phòng</p>
                        <p className="font-semibold text-text-dark text-base mt-0.5">{bookingData.roomName}</p>
                      </div>
                      <div className="grid grid-cols-2 gap-4">
                        <div>
                          <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">Nhận phòng</p>
                          <p className="font-medium text-text-dark mt-0.5">{bookingData.checkInDate}</p>
                        </div>
                        <div>
                          <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">{localize("Trả phòng", "Check-out")}</p>
                          <p className="font-medium text-text-dark mt-0.5">{bookingData.checkOutDate}</p>
                        </div>
                      </div>
                      <div>
                        <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">{localize("Số khách", "Guests")}</p>
                        <p className="font-medium text-text-dark mt-0.5">{bookingData.adultsCount} {localize("người lớn", "adults")}, {bookingData.childrenCount} {localize("trẻ em", "children")}</p>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Navy Total Bar */}
                <div className="bg-primary-navy p-6 text-white flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2">
                  <span className="text-sm font-bold tracking-wider uppercase">{localize("Tổng cộng", "Total")}</span>
                  <div className="text-right">
                    <span className="text-xl sm:text-2xl font-bold text-accent-gold">{formatVND(total)}</span>
                    <p className="text-[10px] text-white/50 uppercase tracking-widest font-semibold mt-0.5">
                      {paymentPlan === "PREPAY_100" ? localize("Thanh toán trước 100%", "Pay 100% now") : localize("Đặt cọc 50%", "50% deposit")}: {formatVND(amountDueNow)}
                    </p>
                  </div>
                </div>
              </div>

              {/* Action Buttons below card */}
              <div className="flex flex-col sm:flex-row gap-4 pt-2">
                <button 
                  onClick={() => window.print()}
                  className="flex-1 border border-[#80632F] text-[#80632F] hover:bg-[#80632F]/5 px-8 py-3.5 font-bold text-xs tracking-widest flex items-center justify-center gap-2 uppercase rounded-sm transition-colors"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <polyline points="6 9 6 2 18 2 18 9" />
                    <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
                    <rect x="6" y="14" width="12" height="8" />
                  </svg>
                  In xác nhận
                </button>
                <Link 
                  href="/"
                  className="flex-1 bg-[#B8944F] hover:bg-[#967538] text-[#091E30] px-8 py-3.5 font-bold text-xs tracking-widest flex items-center justify-center gap-2 uppercase rounded-sm transition-colors shadow-sm"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
                    <polyline points="9 22 9 12 15 12 15 22" />
                  </svg>
                  Về trang chủ
                </Link>
              </div>
            </div>

            {/* Right Column: Your Stay Booking Summary */}
            <div className="bg-white border border-gray-200 p-6 rounded-sm shadow-sm space-y-6">
              <div className="flex items-center gap-3 pb-3 border-b border-gray-100">
                <span className="text-xl">🏨</span>
                <div>
                  <h3 className="font-serif text-lg font-bold text-primary-navy">{localize("Kỳ lưu trú của bạn", "Your stay")}</h3>
                  <p className="text-[10px] text-text-light font-medium tracking-wider uppercase">{localize("Tóm tắt đặt phòng", "Booking summary")}</p>
                </div>
              </div>

              <div className="space-y-3 text-xs font-semibold text-text-dark">
                <div className="flex justify-between">
                  <span className="text-text-light font-medium">{localize("Phòng đã chọn", "Selected rooms")}</span>
                  <span>{String(selectedRoomCount).padStart(2, "0")}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-text-light font-medium">{localize("Sức chứa đã chọn", "Selected capacity")}</span>
                  <span>{selectedGuestCapacity} {localize("khách", "guests")}</span>
                </div>
                <div className="flex justify-between pb-3">
                  <span className="text-text-light font-medium">{localize("Tổng số giờ", "Total hours")}</span>
                  <span>{bookingData.totalHours}</span>
                </div>
                <div className="flex justify-between border-t border-gray-150 pt-3 text-sm font-bold text-primary-navy">
                  <span>Total:</span>
                  <span className="text-base text-accent-gold">{formatVND(total)}</span>
                </div>
              </div>

              {/* Quote block */}
              <div className="bg-gray-50 border border-gray-100 p-4 rounded-sm text-xs italic font-light text-text-dark/80 leading-relaxed">
                &ldquo;Chúng tôi rất hân hạnh được đón tiếp quý khách tại Luxury Hotels. Mọi yêu cầu đặc biệt xin vui lòng liên hệ bộ phận Concierge qua hotline +84 24 1234 5678.&rdquo;
              </div>

              {bookingData.image && (
                <div className="relative h-[180px] overflow-hidden rounded-sm shadow-sm">
                  <ProgressiveImage
                    src={bookingData.image}
                    alt={bookingData.roomName}
                    fill
                    sizes="(min-width: 1024px) 24rem, 100vw"
                    className="object-cover hover:scale-105"
                  />
                </div>
              )}
            </div>

          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#F1F0EA] py-6">
      <div className="mx-auto max-w-7xl space-y-5 px-4 sm:px-6">
        
        {/* Step Indicator Header */}
        <div className="flex items-center justify-center gap-3 border-b border-gray-200/70 pb-4 text-[11px] font-semibold text-text-light sm:gap-6 sm:text-sm">
          <div className="flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-gray-200 text-text-light flex items-center justify-center text-[10px]">1</span>
            <span>Chọn phòng</span>
          </div>
          <div className="h-px w-10 sm:w-16 bg-gray-200" />
          <div className="flex items-center gap-2 text-primary-navy font-bold">
            <span className="w-6 h-6 rounded-full bg-primary-navy text-white flex items-center justify-center text-[10px]">2</span>
            <span>Thông tin & Thanh toán</span>
          </div>
          <div className="h-px w-10 sm:w-16 bg-gray-200" />
          <div className="flex items-center gap-2">
            <span className="w-6 h-6 rounded-full bg-gray-200 text-text-light flex items-center justify-center text-[10px]">3</span>
            <span>Xác nhận</span>
          </div>
        </div>

        {/* Two Columns Grid */}
        <div className="grid grid-cols-1 items-start gap-5 lg:grid-cols-[minmax(0,3fr)_minmax(280px,1fr)]">
          
          {/* Left Columns - Forms */}
          <div className="space-y-5">
            
            {/* Customer Details Form */}
            <div className="space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-sm sm:p-6">
              <h3 className="border-b border-gray-100 pb-3 font-serif text-xl font-bold text-primary-navy">
                {localize("Thông tin khách hàng", "Guest information")}
              </h3>
              {isAuthenticated && (
                <p className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm font-medium text-blue-800">
                  {localize(
                    "Các trường đã có trong hồ sơ được điền tự động và khóa để bảo đảm đúng tài khoản. Thông tin còn thiếu vẫn có thể bổ sung tại đây.",
                    "Profile fields are filled and locked to keep the booking tied to the right account. Missing details can still be completed here.",
                  )}
                </p>
              )}
              
              <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
                <div>
                  <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-text-dark">{localize("Họ và tên", "Full name")} *</label>
                  <input 
                    type="text" 
                    placeholder={localize("Nguyễn Văn A", "Your full name")}
                    value={fullName}
                    onChange={(e) => { setFullName(e.target.value.slice(0, 100)); setPaymentError(""); }}
                    readOnly={profileFieldLocks.fullName || Boolean(pendingReservation)}
                    minLength={2}
                    maxLength={100}
                    autoComplete="name"
                    className="w-full rounded-lg border border-gray-300 bg-white px-4 py-3 text-sm font-medium transition focus:border-accent-gold focus:outline-none focus:ring-2 focus:ring-accent-gold/20 read-only:cursor-default read-only:bg-[#F4F1EA] read-only:text-[#596873]"
                    required
                  />
                </div>
                <div>
                  <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-text-dark">Email *</label>
                  <input 
                    type="email" 
                    placeholder="email@example.com" 
                    value={email}
                    onChange={(e) => { setEmail(e.target.value.slice(0, 254)); setPaymentError(""); }}
                    readOnly={profileFieldLocks.email || Boolean(pendingReservation)}
                    maxLength={254}
                    autoComplete="email"
                    className="w-full rounded-lg border border-gray-300 bg-white px-4 py-3 text-sm font-medium transition focus:border-accent-gold focus:outline-none focus:ring-2 focus:ring-accent-gold/20 read-only:cursor-default read-only:bg-[#F4F1EA] read-only:text-[#596873]"
                    required
                  />
                </div>
                <div>
                  <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-text-dark">{localize("Số điện thoại", "Phone number")} *</label>
                  <input 
                    type="tel" 
                    placeholder={localize("Ví dụ: 0387736436", "Example: +84 387 736 436")}
                    value={phone}
                    onChange={(e) => { setPhone(e.target.value.slice(0, 24)); setPaymentError(""); }}
                    readOnly={profileFieldLocks.phone || Boolean(pendingReservation)}
                    inputMode="tel"
                    maxLength={24}
                    autoComplete="tel"
                    className="w-full rounded-lg border border-gray-300 bg-white px-4 py-3 text-sm font-medium transition focus:border-accent-gold focus:outline-none focus:ring-2 focus:ring-accent-gold/20 read-only:cursor-default read-only:bg-[#F4F1EA] read-only:text-[#596873]"
                    required
                  />
                </div>
                <div>
                  <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-text-dark">{localize("Quốc gia", "Country")}</label>
                  <select 
                    value={country}
                    onChange={(e) => setCountry(e.target.value)}
                    disabled={profileFieldLocks.country || Boolean(pendingReservation)}
                    className="w-full rounded-lg border border-gray-300 bg-white px-4 py-3 text-sm font-medium transition focus:border-accent-gold focus:outline-none focus:ring-2 focus:ring-accent-gold/20 disabled:bg-[#F4F1EA] disabled:text-[#596873]"
                  >
                    <option value="Việt Nam">{localize("Việt Nam", "Vietnam")}</option>
                    <option value="Mỹ">{localize("Mỹ", "United States")}</option>
                    <option value="Nhật Bản">{localize("Nhật Bản", "Japan")}</option>
                    <option value="Hàn Quốc">{localize("Hàn Quốc", "South Korea")}</option>
                  </select>
                </div>
              </div>

              <div>
                <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-text-dark">{localize("Yêu cầu đặc biệt", "Special requests")}</label>
                <textarea 
                  rows={2}
                  placeholder={localize("Ví dụ: Phòng tầng cao, nhận phòng sớm...", "Example: High floor, early check-in...")}
                  value={specialRequest}
                  onChange={(e) => { setSpecialRequest(e.target.value.slice(0, 500)); setPaymentError(""); }}
                  readOnly={Boolean(pendingReservation)}
                  maxLength={500}
                  className="w-full rounded-lg border border-gray-300 bg-white px-4 py-3 text-sm font-medium transition focus:border-accent-gold focus:outline-none focus:ring-2 focus:ring-accent-gold/20 read-only:cursor-default read-only:bg-[#F4F1EA] read-only:text-[#596873]"
                />
                <span className="mt-1 block text-right text-[10px] font-medium text-text-light">{specialRequest.length}/500</span>
              </div>
            </div>

            {/* Compact summary; the complete selector lives in a viewport modal. */}
            <section className="rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-sm sm:p-6">
              <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0">
                  <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Nâng cấp kỳ nghỉ", "Enhance your stay")}</p>
                  <h3 className="mt-1 font-serif text-xl font-bold text-primary-navy">{localize("Dịch vụ thêm", "Add-on services")}</h3>
                  <p className="mt-1 text-xs leading-5 text-[#66727C]">
                    {selectedAddOns.length > 0
                      ? localize(`${selectedAddOns.length} dịch vụ đã chọn · ${formatVND(addOnSummaryTotal)}`, `${selectedAddOns.length} selected · ${formatVND(addOnSummaryTotal)}`)
                      : localize("Chọn khi cần; trang thanh toán vẫn gọn và dễ kiểm tra.", "Choose only when needed while keeping checkout concise.")}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setIsAddOnModalOpen(true)}
                  disabled={isAddOnLoading}
                  className="inline-flex min-h-11 shrink-0 cursor-pointer items-center justify-center gap-2 rounded-xl border border-[#B8944F] bg-[#F0EADF]/65 px-5 text-sm font-bold text-[#0F2A43] transition duration-200 hover:bg-[#E8DDC7] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-wait disabled:opacity-60"
                >
                  {isAddOnLoading
                    ? localize("Đang tải...", "Loading...")
                    : selectedAddOns.length > 0
                      ? localize("Xem và chỉnh sửa", "Review and edit")
                      : localize("Chọn dịch vụ", "Choose services")}
                  <span aria-hidden="true">→</span>
                </button>
              </div>
              {selectedAddOns.length > 0 && (
                <ul className="mt-4 flex flex-wrap gap-2 border-t border-[#0F2A43]/10 pt-4" aria-label={localize("Dịch vụ đã chọn", "Selected services")}>
                  {selectedAddOns.slice(0, 3).map(({ service, selection }) => (
                    <li key={service.id} className="rounded-full border border-[#B8944F]/35 bg-[#FBFAF6] px-3 py-1.5 text-xs font-semibold text-[#0F2A43]">
                      {localize(service.name, service.nameEn)} × {selection.quantity}
                    </li>
                  ))}
                  {selectedAddOns.length > 3 && (
                    <li className="rounded-full bg-[#0F2A43] px-3 py-1.5 text-xs font-bold text-white">+{selectedAddOns.length - 3}</li>
                  )}
                </ul>
              )}
              {addOnLoadError && <p role="status" className="mt-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs font-semibold leading-5 text-amber-900">{addOnLoadError}</p>}
            </section>

            <div className="grid items-start gap-5 xl:grid-cols-2">
            {/* Payment Method Selector */}
            <div className="space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-sm sm:p-6">
              <h3 className="border-b border-gray-100 pb-3 font-serif text-xl font-bold text-primary-navy">
                {localize("Phương thức thanh toán", "Payment method")}
              </h3>
              <fieldset disabled={Boolean(pendingReservation)}>
                <legend className="mb-3 text-xs font-bold uppercase tracking-wider text-text-dark">
                  {localize("Số tiền thanh toán trước", "Prepayment amount")}
                </legend>
                <div className="grid gap-3 sm:grid-cols-2">
                  {([
                    ["DEPOSIT_50", localize("Đặt cọc 50%", "50% deposit"), formatVND(deposit50)],
                    ["PREPAY_100", localize("Thanh toán 100%", "Pay 100% now"), formatVND(total)],
                  ] as const).map(([value, label, amount]) => (
                    <label key={value} className={`cursor-pointer rounded-xl border p-4 transition ${paymentPlan === value ? "border-[#80632F] bg-[#F0EADF] ring-1 ring-[#80632F]/40" : "border-[#0F2A43]/10 bg-white hover:border-[#80632F]/50"}`}>
                      <input type="radio" name="paymentPlan" value={value} checked={paymentPlan === value} onChange={() => setPaymentPlan(value)} className="sr-only" />
                      <span className="block text-sm font-bold text-primary-navy">{label}</span>
                      <span className="mt-1 block text-lg font-black tabular-nums text-[#80632F]">{amount}</span>
                    </label>
                  ))}
                </div>
              </fieldset>
              <div className="flex items-center justify-between gap-4 rounded-sm border border-[#80632F] bg-[#F0EADF]/30 p-4 ring-1 ring-[#80632F]">
                <div className="flex min-w-0 items-center gap-3">
                  <div className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full border-2 border-[#80632F]" aria-hidden="true">
                    <div className="h-2.5 w-2.5 rounded-full bg-[#80632F]" />
                  </div>
                  <div>
                    <p className="text-sm font-bold text-primary-navy">{localize("Thanh toán QR", "QR payment")}</p>
                    <p className="mt-0.5 text-[11px] font-medium text-text-light">
                      {localize("Quét mã bằng ứng dụng ngân hàng để chuyển khoản", "Scan with your banking app to transfer")}
                    </p>
                  </div>
                </div>
                <span className="shrink-0 rounded border border-primary-navy/15 bg-white px-2 py-1 text-[10px] font-black text-primary-navy">VIETQR</span>
              </div>
            </div>

            <div ref={termsSectionRef} className="scroll-mt-24 space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-sm sm:p-6">
              <h3 className="border-b border-gray-100 pb-3 font-serif text-xl font-bold text-primary-navy">
                {localize("Xác nhận điều khoản", "Accept the terms")}
              </h3>
              <p className="text-sm text-text-light font-medium leading-relaxed">
                {localize(
                  "Bạn sẽ được chuyển tới trang thanh toán QR để quét mã và theo dõi trạng thái an toàn.",
                  "You will continue to the QR payment page to scan the code and securely track payment status."
                )}
              </p>
              <label className="flex items-start gap-3 text-xs text-text-light font-medium cursor-pointer pt-2">
                <input
                  ref={termsCheckboxRef}
                  type="checkbox"
                  checked={agree}
                  onChange={(e) => {
                    setAgree(e.target.checked);
                    if (e.target.checked) setPaymentError("");
                  }}
                  className="mt-0.5 rounded border-gray-300 text-accent-gold focus:ring-accent-gold"
                  required
                />
                <span>
                  {localize("Tôi đã đọc và đồng ý với", "I have read and agree to the")} <Link href="/terms" className="text-accent-gold hover:underline">{localize("Điều khoản & Điều kiện", "Terms & Conditions")}</Link> {localize("và", "and")} <Link href="/privacy" className="text-accent-gold hover:underline">{localize("Chính sách bảo mật", "Privacy Policy")}</Link> {localize("của Luxury Hotel.", "of Luxury Hotel.")}
                </span>
              </label>
              {paymentError && (
                <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-xs font-medium leading-5 text-rose-800">
                  {paymentError}
                </p>
              )}
              {pendingReservation && (
                <p className="rounded-lg border border-sky-200 bg-sky-50 px-4 py-3 text-xs font-medium leading-5 text-sky-900">
                  {localize(
                    `Phiên ${pendingReservation.reservationCode} đã được tạo. Thử lại thanh toán sẽ dùng đúng phiên này, không tạo trùng đơn.`,
                    `Reservation ${pendingReservation.reservationCode} was created. Retrying payment reuses this reservation and will not create a duplicate.`
                  )}
                </p>
              )}
            </div>
            </div>

          </div>

          {/* Right Column - Booking Summary */}
          <div className="space-y-4 rounded-xl border border-[#0F2A43]/10 bg-white p-5 shadow-md lg:sticky lg:top-24">
            <h3 className="font-sans text-lg font-bold text-primary-navy pb-3 border-b border-gray-100">
              {localize("Tóm tắt đặt phòng", "Booking summary")}
            </h3>

            {/* Room Info */}
            <div className="flex gap-4">
              <div className="relative h-16 w-24 shrink-0 overflow-hidden rounded-sm border border-gray-100 bg-[#E5E9ED]">
                {bookingData.image ? (
                  <ProgressiveImage src={bookingData.image} alt={bookingData.roomName} fill sizes="6rem" className="object-cover" />
                ) : (
                  <span className="flex h-full items-center justify-center text-[10px] font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</span>
                )}
              </div>
              <div className="min-w-0">
                <h4 className="font-serif text-base font-bold text-primary-navy truncate">{bookingData.roomName}</h4>
                <p className="mt-0.5 text-xs font-medium text-text-light">{localize("Diện tích", "Area")}: {bookingData.size}</p>
              </div>
            </div>

            <section className="overflow-hidden rounded-lg border border-[#0F2A43]/10 bg-[#FBFAF6]">
              <div className="border-b border-[#0F2A43]/10 px-3 py-2">
                <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#80632F]">
                  {localize("Giá từng hạng phòng", "Price by room type")}
                </p>
              </div>
              <div className="divide-y divide-[#0F2A43]/10">
                {(hasAuthoritativeQuote
                  ? pricingQuote.lines.map((line) => ({
                      roomTypeId: line.roomTypeId,
                      roomTypeName: line.roomTypeName,
                      quantity: line.quantity,
                      includedGuestsPerRoom: line.includedGuestsPerRoom,
                      maxGuestsPerRoom: line.maxGuestsPerRoom,
                      extraGuestPrice: line.extraGuestPrice,
                      extraGuestCount: line.extraGuestCount,
                      packageName: line.appliedPackage === "OVERNIGHT"
                        ? localize("Qua đêm", "Overnight")
                        : line.appliedPackage === "DAILY"
                          ? localize("Ngày đêm", "Daily")
                          : localize("Theo giờ", "Hourly"),
                      unitPrice: line.quantity > 0 ? line.roomCharge / line.quantity : 0,
                      roomCharge: line.roomCharge,
                      extraGuestCharge: line.extraGuestCharge,
                    }))
                  : []
                ).map((line) => (
                  <div key={line.roomTypeId} className="grid grid-cols-[minmax(0,1fr)_auto] gap-3 px-3 py-3 text-xs">
                    <div className="min-w-0">
                      <p className="truncate font-bold text-[#0F2A43]">{line.quantity} × {line.roomTypeName}</p>
                      <p className="mt-0.5 text-[11px] font-medium text-[#66727C]">
                        {formatVND(line.unitPrice)}/{localize("phòng", "room")} · {line.packageName}
                      </p>
                      <p className="mt-0.5 text-[10px] font-medium text-[#66727C]">
                        {localize(
                          `Giá gồm ${line.includedGuestsPerRoom} khách/phòng · tối đa ${line.maxGuestsPerRoom}`,
                          `Includes ${line.includedGuestsPerRoom} guests/room · max ${line.maxGuestsPerRoom}`,
                        )}
                      </p>
                      {line.extraGuestCount > 0 && line.extraGuestCharge > 0 && (
                        <p className="mt-1 font-semibold text-[#80632F]">
                          {localize(
                            `${line.extraGuestCount} khách thêm · ${formatVND(line.extraGuestPrice)}/người/mỗi chu kỳ lưu trú`,
                            `${line.extraGuestCount} extra ${line.extraGuestCount === 1 ? "guest" : "guests"} · ${formatVND(line.extraGuestPrice)}/person/stay cycle`,
                          )}: +{formatVND(line.extraGuestCharge)}
                        </p>
                      )}
                    </div>
                    <p className="self-center font-bold tabular-nums text-[#0F2A43]">
                      {formatVND(line.roomCharge + line.extraGuestCharge)}
                    </p>
                  </div>
                ))}
              </div>
            </section>

            {/* Dates & Guests */}
            <div className="grid grid-cols-2 gap-4 border-t border-b border-gray-100 py-4 text-xs font-semibold text-text-dark">
              <div>
                <p className="mb-1 font-medium uppercase tracking-wider text-text-light">{localize("Ngày đến", "Arrival")}</p>
                <p>{formatDateTimeVietnamese(bookingData.checkInDate)}</p>
              </div>
              <div>
                <p className="mb-1 font-medium uppercase tracking-wider text-text-light">{localize("Ngày đi", "Departure")}</p>
                <p>{formatDateTimeVietnamese(bookingData.checkOutDate)}</p>
              </div>
              <div className="col-span-2 pt-2 border-t border-gray-100/50">
                <p className="mb-1 font-medium uppercase tracking-wider text-text-light">{localize("Khách", "Guests")}</p>
                <p>{localize(
                  `${bookingData.adultsCount} người lớn, ${bookingData.childrenCount} trẻ em · Sức chứa ${selectedGuestCapacity}`,
                  `${bookingData.adultsCount} adults, ${bookingData.childrenCount} children · Capacity ${selectedGuestCapacity}`,
                )}</p>
              </div>
            </div>

            {bookingData.selectedRooms.length > 1 && (
              <section className="space-y-3 rounded-lg border border-[#0F2A43]/10 bg-[#F7F4EC] p-3">
                <div className="flex items-center justify-between gap-3">
                  <div>
                    <p className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#80632F]">
                      {localize("Phân bổ khách", "Guest allocation")}
                    </p>
                    <p className="mt-0.5 text-[11px] font-medium text-[#66727C]">
                      {localize(
                        "Đề xuất dùng hết suất đã gồm giá và ưu tiên phụ thu thấp hơn. Bạn vẫn có thể đổi từng hạng; giá sẽ tự tính lại.",
                        "The suggestion uses every included slot first, then the lowest surcharge. You can still change each room type and the price will update automatically.",
                      )}
                    </p>
                  </div>
                  <span className={`rounded-full px-2.5 py-1 text-[11px] font-black tabular-nums ${
                    quotedLineGuestTotal === declaredGuestCount
                      ? "bg-emerald-100 text-emerald-800"
                      : "bg-amber-100 text-amber-900"
                  }`}>
                    {quotedLineGuestTotal}/{declaredGuestCount}
                  </span>
                </div>
                <div className="space-y-2">
                  {bookingData.selectedRooms.map((room) => {
                    const capacity = room.quantity * room.maxGuestsPerRoom;
                    const includedCapacity = room.quantity * room.includedGuestsPerRoom;
                    return (
                      <label key={room.roomTypeId} className="flex min-h-11 items-center justify-between gap-3 rounded-lg border border-[#0F2A43]/10 bg-white px-3 py-2">
                        <span className="min-w-0">
                          <span className="block truncate text-xs font-bold text-[#0F2A43]">
                            {room.quantity} × {room.roomName}
                          </span>
                          <span className="block text-[10px] font-medium text-[#66727C]">
                            {localize(
                              `Giá gồm ${includedCapacity} khách · tối đa ${capacity}`,
                              `Includes ${includedCapacity} guests · maximum ${capacity}`,
                            )}
                          </span>
                        </span>
                        <select
                          aria-label={localize(`Số khách cho ${room.roomName}`, `Guests for ${room.roomName}`)}
                          value={lineGuestCounts[room.roomTypeId] || ""}
                          disabled={Boolean(pendingReservation)}
                          onChange={(event) => {
                            const nextValue = Number(event.target.value);
                            setLineGuestCounts((current) => ({
                              ...current,
                              [room.roomTypeId]: nextValue,
                            }));
                            setPricingQuote(null);
                            setPricingQuoteMode("checking");
                            setPricingQuoteError("");
                            setPaymentError("");
                            setIsConfirmationOpen(false);
                          }}
                          className="min-h-11 w-36 shrink-0 cursor-pointer rounded-lg border border-[#0F2A43]/20 bg-white px-2 text-sm font-bold text-[#0F2A43] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                        >
                          <option value="" disabled>—</option>
                          {Array.from(
                            { length: capacity - room.quantity + 1 },
                            (_, index) => index + room.quantity,
                          ).map((value) => (
                            <option key={value} value={value}>
                              {localize(
                                `${value} khách${value > includedCapacity ? ` (+${value - includedCapacity} phụ thu)` : ""}`,
                                `${value} guests${value > includedCapacity ? ` (+${value - includedCapacity} surcharge)` : ""}`,
                              )}
                            </option>
                          ))}
                        </select>
                      </label>
                    );
                  })}
                </div>
              </section>
            )}

            <div aria-live="polite">
              {pricingQuoteMode === "checking" && !pendingReservation && (
                <p className="flex items-center gap-2 rounded-lg border border-sky-100 bg-sky-50 px-3 py-2 text-xs font-semibold text-sky-800">
                  <span className="h-3.5 w-3.5 animate-spin rounded-full border-2 border-sky-700 border-t-transparent" aria-hidden="true" />
                  {localize("Đang kiểm tra giá chính xác...", "Checking the authoritative price...")}
                </p>
              )}
              {pricingQuoteMode === "v2" && pricingQuote && (
                <div className="space-y-2">
                  <p className="rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-semibold text-emerald-800">
                    {localize(`Đã khóa báo giá · ${displayedPackageLabel}`, `Quote ready · ${displayedPackageLabel}`)}
                  </p>
                  {isEarlyMorningOvernight && (
                    <p className="rounded-lg border border-[#B8944F]/35 bg-[#F7F1E5] px-3 py-2 text-xs font-semibold leading-relaxed text-[#6F5425]">
                      {localize(
                        "Nhận phòng từ 00:00 đến trước 05:00 được áp dụng ngay gói qua đêm và trả muộn nhất 10:00.",
                        "Check-in from 00:00 until before 05:00 uses the overnight package immediately, with checkout no later than 10:00.",
                      )}
                    </p>
                  )}
                </div>
              )}
              {pricingQuoteMode === "error" && !pendingReservation && (
                <div className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-semibold text-rose-800">
                  <p>{pricingQuoteError}</p>
                  {quoteRequestPayload && (
                    <button
                      type="button"
                      onClick={() => setQuoteRefreshNonce((value) => value + 1)}
                      className="mt-2 min-h-11 cursor-pointer rounded-lg border border-rose-300 bg-white px-3 font-bold transition hover:bg-rose-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-rose-500"
                    >
                      {localize("Thử kiểm tra lại", "Retry price check")}
                    </button>
                  )}
                </div>
              )}
            </div>

            {/* Prices details */}
            <div className="space-y-2 text-sm">
              <div className="flex justify-between text-text-light font-medium">
                <span>
                  {hasAuthoritativeQuote
                    ? localize(`Giá phòng · ${displayedPackageLabel}`, `Room charge · ${displayedPackageLabel}`)
                    : localize("Giá phòng", "Room charge")}
                </span>
                <span>{formatVND(roomTotal)}</span>
              </div>
              {extraGuestTotal > 0 && (
                <div className="flex justify-between text-text-light font-medium">
                  <span>{localize("Khách thêm", "Extra guests")}</span>
                  <span>{formatVND(extraGuestTotal)}</span>
                </div>
              )}
              {displayedAddOnTotal > 0 && <div className="flex justify-between text-text-light font-medium"><span>{localize("Dịch vụ thêm", "Add-on services")}</span><span>{formatVND(displayedAddOnTotal)}</span></div>}
              <div className="flex justify-between text-text-light font-medium">
                <span>{paymentPlan === "PREPAY_100" ? localize("Thanh toán trước 100%", "Pay 100% now") : localize("Đặt cọc 50%", "50% deposit")}</span>
                <span>{formatVND(amountDueNow)}</span>
              </div>
              <div className="flex justify-between border-t border-gray-200 pt-3 text-base font-bold text-primary-navy">
                <span>{localize("THANH TOÁN HÔM NAY", "DUE TODAY")}</span>
                <span className="text-accent-gold">{formatVND(amountDueNow)}</span>
              </div>
            </div>

            <button 
              type="button"
              onClick={handleRequestBooking}
              disabled={isBookingActionDisabled}
              className="mt-2 w-full rounded-lg bg-[#80632F] py-3 text-sm font-bold uppercase tracking-widest text-white shadow-sm transition-colors hover:bg-[#735630] disabled:cursor-wait disabled:opacity-60"
            >
              {pricingQuoteMode === "checking" && !pendingReservation
                ? localize("Đang kiểm tra giá...", "Checking price...")
                : isSubmitting
                  ? localize("Đang xử lý...", "Processing...")
                  : localize("Đặt phòng", "Book now")}
            </button>

            <p className="text-center text-[10px] text-text-light font-medium">
              {localize("Thanh toán thành công sẽ tạo đơn DRAFT; đơn chỉ được CONFIRMED sau khi khách sạn xác nhận.", "A successful payment creates a DRAFT reservation; it becomes CONFIRMED only after hotel approval.")}
            </p>
          </div>

        </div>

      </div>

      <ViewportModal open={isAddOnModalOpen} onClose={() => setIsAddOnModalOpen(false)} labelledBy="booking-addon-modal-title" panelClassName="max-w-4xl" testId="booking-addon-modal">
        <section className="flex min-h-0 flex-1 flex-col bg-[#FBFAF6]">
          <header className="flex items-start justify-between gap-4 border-b border-white/10 bg-[#0F2A43] px-5 py-4 text-white sm:px-7 sm:py-5">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D8C398]">{localize("Nâng cấp kỳ nghỉ", "Enhance your stay")}</p>
              <h2 id="booking-addon-modal-title" className="mt-1 font-serif text-2xl font-bold">{localize("Chọn dịch vụ thêm", "Choose add-on services")}</h2>
              <p className="mt-1 max-w-2xl text-xs leading-5 text-white/72">{localize("Mọi thay đổi sẽ được hệ thống báo giá lại tự động trước khi bạn đặt phòng.", "Every change is automatically repriced before you book.")}</p>
            </div>
            <button type="button" onClick={() => setIsAddOnModalOpen(false)} aria-label={localize("Đóng", "Close")} className="flex h-11 w-11 shrink-0 cursor-pointer items-center justify-center rounded-full border border-white/25 text-xl transition hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#D8C398]">×</button>
          </header>
          <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto p-4 sm:p-6">
            <BookingAddOnSelector
              services={addOnCatalog}
              selections={addOnSelections}
              guestCount={declaredGuestCount}
              nights={stayNights}
              authoritativeLineTotals={authoritativeServiceTotals}
              loading={isAddOnLoading}
              disabled={Boolean(pendingReservation)}
              onChange={(next) => {
                setAddOnSelections(next);
                setPricingQuote(null);
                setPricingQuoteMode("checking");
                setPricingQuoteError("");
                setPaymentError("");
                setIsConfirmationOpen(false);
              }}
            />
          </div>
          <footer className="flex flex-col gap-3 border-t border-[#0F2A43]/10 bg-white px-5 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-7">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Đã chọn", "Selected")}</p>
              <p className="mt-1 text-sm font-bold text-[#0F2A43]">{selectedAddOns.length} {localize("dịch vụ", "services")} · <span className="tabular-nums text-[#80632F]">{formatVND(addOnSummaryTotal)}</span></p>
            </div>
            <button type="button" onClick={() => setIsAddOnModalOpen(false)} className="min-h-11 cursor-pointer rounded-xl bg-[#0F2A43] px-6 text-sm font-bold text-white transition hover:bg-[#091E30] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">{localize("Xong", "Done")}</button>
          </footer>
        </section>
      </ViewportModal>

      <ViewportModal open={isConfirmationOpen} onClose={() => setIsConfirmationOpen(false)} labelledBy="booking-confirmation-title" busy={isSubmitting} panelClassName="max-w-xl" testId="booking-confirmation-modal">
          <section className="flex min-h-0 flex-1 flex-col bg-[#FBFAF6]">
            <header className="bg-[#091E30] px-6 py-5 text-white sm:px-7">
              <p className="text-xs font-bold uppercase tracking-[0.18em] text-[#D8C398]">{localize("Kiểm tra lần cuối", "Final review")}</p>
              <h2 id="booking-confirmation-title" className="mt-2 font-serif text-2xl font-bold">{localize("Xác nhận đặt phòng", "Confirm reservation")}</h2>
            </header>
            <div className="lux-scrollbar min-h-0 flex-1 space-y-5 overflow-y-auto px-6 py-6 sm:px-7">
              <div className="rounded-xl border border-[#0F2A43]/12 bg-[#E5E9ED] p-4">
                <p className="font-bold text-[#091E30]">{bookingData.roomName}</p>
                <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2">
                  <div><dt className="text-xs font-semibold text-[#66727C]">{localize("Nhận phòng", "Check-in")}</dt><dd className="mt-1 font-bold">{formatDateTimeVietnamese(bookingData.checkInDate)}</dd></div>
                  <div><dt className="text-xs font-semibold text-[#66727C]">{localize("Trả phòng", "Check-out")}</dt><dd className="mt-1 font-bold">{formatDateTimeVietnamese(bookingData.checkOutDate)}</dd></div>
                </dl>
              </div>
              {selectedAddOns.length > 0 && (
                <div className="rounded-xl border border-[#B8944F]/35 bg-[#F0EADF]/55 p-4">
                  <p className="text-xs font-bold uppercase tracking-[0.14em] text-[#80632F]">{localize("Dịch vụ đã chọn", "Selected services")}</p>
                  <ul className="mt-3 space-y-2 text-sm">
                    {selectedAddOns.map(({ service, selection, total: serviceTotal }) => (
                      <li key={service.id} className="flex items-start justify-between gap-3">
                        <span className="font-semibold text-[#0F2A43]">{localize(service.name, service.nameEn)} × {selection.quantity}</span>
                        <span className="shrink-0 font-bold tabular-nums text-[#80632F]">{formatVND(serviceTotal)}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="flex items-end justify-between gap-4 border-b border-[#0F2A43]/10 pb-4">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-[0.14em] text-[#66727C]">{paymentPlan === "PREPAY_100" ? localize("Trả trước 100%", "Pay 100% now") : localize("Đặt cọc 50%", "50% deposit")}</p>
                  <p className="mt-1 text-sm text-[#66727C]">{localize("Thanh toán QR", "QR payment")}</p>
                </div>
                <strong className="text-xl tabular-nums text-[#80632F]">{formatVND(amountDueNow)}</strong>
              </div>
              <p className="text-sm leading-6 text-[#66727C]">{localize("Sau khi xác nhận, hệ thống tạo đơn và chuyển bạn đến mã QR thanh toán. Hãy kiểm tra đúng thời gian, số phòng và số tiền trước khi tiếp tục.", "After confirmation, we create the reservation and open its payment QR. Check the stay time, room quantity, and amount before continuing.")}</p>
              {paymentError && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm font-medium text-rose-700">{paymentError}</p>}
            </div>
            <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 bg-white px-6 py-4 sm:flex-row sm:justify-end sm:px-7">
              <button type="button" disabled={isSubmitting} onClick={() => setIsConfirmationOpen(false)} className="min-h-11 rounded-xl border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] hover:bg-[#E5E9ED] disabled:opacity-50">{localize("Quay lại", "Go back")}</button>
              <button type="button" disabled={isSubmitting} onClick={() => void handleConfirmBooking()} className="min-h-11 rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30] disabled:cursor-wait disabled:opacity-60">{isSubmitting ? localize("Đang xử lý...", "Processing...") : localize("Xác nhận đặt phòng", "Confirm reservation")}</button>
            </footer>
          </section>
      </ViewportModal>
    </div>
  );
}

export default function BookingPage() {
  return (
    <Suspense fallback={
      <div className="min-h-screen flex items-center justify-center bg-[#F1F0EA]">
        <div className="flex flex-col items-center gap-4">
          <div className="w-12 h-12 border-4 border-primary-navy border-t-accent-gold rounded-full animate-spin"></div>
          <p className="text-primary-navy font-semibold">Đang chuẩn bị thanh toán QR...</p>
        </div>
      </div>
    }>
      <BookingFormContent />
    </Suspense>
  );
}
