"use client";

import React, { useCallback, useState, useEffect, useMemo } from "react";
import Image from "next/image";
import { apiClient, cachedGet } from "@/lib/api";
import { resolveMediaSource } from "@/lib/media-url";
import Toast from "@/components/UI/Toast";
import ImageUploadField from "@/components/UI/ImageUploadField";
import ViewportModal from "@/components/UI/ViewportModal";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

type CapacityFilter = "ALL" | "ONE_TWO" | "THREE_FOUR" | "FIVE_PLUS";
type StatusFilter = "ALL" | "ACTIVE" | "INACTIVE";
type RoomTypeSort = "DEFAULT" | "PRICE_ASC" | "PRICE_DESC" | "CAPACITY_DESC";

interface Facility {
  id: number;
  facilityName: string;
  facilityNameEn?: string;
  type: string;
  description: string;
  imageUrl: string;
}

interface RoomType {
  id: number;
  code?: string;
  typeName: string;
  typeNameEn?: string;
  description: string;
  descriptionEn?: string;
  active: boolean;
  packagePricingEnabled?: boolean;
  pricingAvailable?: boolean;
  includedGuests?: number | null;
  firstBlockMinutes?: number | null;
  firstBlockPrice?: number | null;
  extraUnitMinutes?: number | null;
  extraUnitPrice?: number | null;
  overnightPrice?: number | null;
  dailyPrice?: number | null;
  extraGuestPrice?: number | null;
  maxGuests: number;
  imageUrl: string;
  imageUrls?: string[];
  facilities: Facility[];
}

interface RoomTypeFormData {
  id: number;
  typeName: string;
  typeNameEn: string;
  maxGuests: string;
  includedGuests: string;
  firstBlockPrice: string;
  extraUnitPrice: string;
  overnightPrice: string;
  dailyPrice: string;
  extraGuestPrice: string;
  imageUrl: string;
  imageUrls: string[];
  description: string;
  descriptionEn: string;
  facilityIds: number[];
}

type RateAmountField = "firstBlockPrice" | "extraUnitPrice" | "overnightPrice" | "dailyPrice" | "extraGuestPrice";

const emptyRoomTypeForm = (): RoomTypeFormData => ({
  id: 0,
  typeName: "",
  typeNameEn: "",
  maxGuests: "3",
  includedGuests: "2",
  firstBlockPrice: "",
  extraUnitPrice: "",
  overnightPrice: "",
  dailyPrice: "",
  extraGuestPrice: "50000",
  imageUrl: "",
  imageUrls: ["", "", ""],
  description: "",
  descriptionEn: "",
  facilityIds: [],
});

const validateRatePlan = (form: RoomTypeFormData) => {
  const positiveAmounts = [
    ["Giá 2 giờ đầu", form.firstBlockPrice],
    ["Giá mỗi giờ thêm", form.extraUnitPrice],
    ["Giá qua đêm", form.overnightPrice],
    ["Giá ngày đêm", form.dailyPrice],
  ] as const;
  for (const [label, raw] of positiveAmounts) {
    const amount = Number(raw);
    if (!Number.isSafeInteger(amount) || amount <= 0) {
      return `${label} phải là số VND nguyên lớn hơn 0`;
    }
  }
  const extraGuestPrice = Number(form.extraGuestPrice);
  if (!Number.isSafeInteger(extraGuestPrice) || extraGuestPrice < 0) {
    return "Phụ thu khách thêm phải là số VND nguyên không âm";
  }
  const includedGuests = Number(form.includedGuests);
  const maxGuests = Number(form.maxGuests);
  if (!Number.isInteger(includedGuests) || includedGuests < 1 || includedGuests > maxGuests) {
    return "Số khách đã bao gồm phải từ 1 đến sức chứa tối đa";
  }
  if (Number(form.firstBlockPrice) > Number(form.overnightPrice)) {
    return "Giá 2 giờ đầu không được lớn hơn giá qua đêm";
  }
  if (Number(form.overnightPrice) > Number(form.dailyPrice)) {
    return "Giá qua đêm không được lớn hơn giá ngày đêm";
  }
  return null;
};

const normalizeImageSlots = (imageUrls: string[] | undefined, fallbackImage?: string, maxImages = 3) => {
  const normalized = Array.from(
    new Set([...(imageUrls || []), fallbackImage || ""].map((image) => image.trim()).filter(Boolean)),
  ).slice(0, maxImages);
  return [...normalized, ...Array(Math.max(0, maxImages - normalized.length)).fill("")];
};

const getApiErrorMessage = (error: unknown, fallback: string) => {
  if (typeof error !== "object" || error === null || !("response" in error)) return fallback;
  const response = (error as { response?: { data?: { message?: unknown } } }).response;
  return typeof response?.data?.message === "string" ? response.data.message : fallback;
};

export default function RoomTypesManagement() {
  const { isAdmin, role } = useDashboardRole();
  const { localeTag, localize } = useLanguage();
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);
  const [facilities, setFacilities] = useState<Facility[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [capacityFilter, setCapacityFilter] = useState<CapacityFilter>("ALL");
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [sortOrder, setSortOrder] = useState<RoomTypeSort>("DEFAULT");
  const [uploadingSlots, setUploadingSlots] = useState<Set<number>>(() => new Set());
  const [isSaving, setIsSaving] = useState(false);
  const isUploading = uploadingSlots.size > 0;

  const setSlotUploading = (index: number, uploading: boolean) => {
    setUploadingSlots((current) => {
      const next = new Set(current);
      if (uploading) next.add(index);
      else next.delete(index);
      return next;
    });
  };

  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isEditOpen, setIsEditOpen] = useState(false);
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);
  const [isStatusOpen, setIsStatusOpen] = useState(false);
  const [statusReason, setStatusReason] = useState("");

  const [formData, setFormData] = useState<RoomTypeFormData>(emptyRoomTypeForm);

  const [selectedRoomType, setSelectedRoomType] = useState<RoomType | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const showToast = useCallback((message: string, type: "success" | "error" | "info") => {
    setToast({ message, type });
  }, []);

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    try {
      const [typesRes, facRes] = await Promise.all([
        apiClient.get<{ data?: RoomType[] }>(isAdmin ? "/api/room-types/admin" : "/api/room-types"),
        cachedGet("/api/facilities"),
      ]);

      setRoomTypes(Array.isArray(typesRes.data?.data) ? typesRes.data.data : []);
      if (facRes.data && facRes.data.data) {
        setFacilities(facRes.data.data);
      }
    } catch {
      showToast("Không thể tải danh sách room type từ backend", "error");
    } finally {
      setIsLoading(false);
    }
  }, [isAdmin, showToast]);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const filteredRoomTypes = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase();
    const matched = roomTypes.filter((type) => {
      const matchesSearch = !keyword || (
        type.typeName.toLowerCase().includes(keyword) ||
        (type.typeNameEn || "").toLowerCase().includes(keyword) ||
        (type.description || "").toLowerCase().includes(keyword) ||
        (type.descriptionEn || "").toLowerCase().includes(keyword) ||
        (type.facilities || []).some((facility) =>
          facility.facilityName.toLowerCase().includes(keyword)
          || (facility.facilityNameEn || "").toLowerCase().includes(keyword),
        )
      );
      const capacity = Number(type.maxGuests || 0);
      const matchesCapacity = capacityFilter === "ALL"
        || (capacityFilter === "ONE_TWO" && capacity >= 1 && capacity <= 2)
        || (capacityFilter === "THREE_FOUR" && capacity >= 3 && capacity <= 4)
        || (capacityFilter === "FIVE_PLUS" && capacity >= 5);
      const matchesStatus = statusFilter === "ALL"
        || (statusFilter === "ACTIVE" && type.active)
        || (statusFilter === "INACTIVE" && !type.active);
      return matchesSearch && matchesCapacity && matchesStatus;
    });

    if (sortOrder === "DEFAULT") return matched;
    return [...matched].sort((left, right) => {
      if (sortOrder === "PRICE_ASC") return Number(left.overnightPrice || 0) - Number(right.overnightPrice || 0);
      if (sortOrder === "PRICE_DESC") return Number(right.overnightPrice || 0) - Number(left.overnightPrice || 0);
      return Number(right.maxGuests || 0) - Number(left.maxGuests || 0);
    });
  }, [capacityFilter, roomTypes, searchQuery, sortOrder, statusFilter]);

  const openCreateModal = () => {
    setUploadingSlots(new Set());
    setFormData(emptyRoomTypeForm());
    setIsCreateOpen(true);
  };

  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isUploading || isSaving) {
      showToast(localize("Vui lòng đợi ảnh tải xong.", "Please wait for the image upload to finish."), "info");
      return;
    }
    if (!formData.typeName.trim() || Number(formData.maxGuests) < 1) {
      showToast("Vui lòng nhập tên và sức chứa loại phòng", "error");
      return;
    }
    const pricingError = validateRatePlan(formData);
    if (pricingError) {
      showToast(pricingError, "error");
      return;
    }

    setIsSaving(true);
    try {
      const imageUrls = Array.from(new Set(formData.imageUrls.map((image) => image.trim()).filter(Boolean))).slice(0, 3);
      await apiClient.post("/api/room-types", {
        typeName: formData.typeName,
        typeNameEn: formData.typeNameEn,
        maxGuests: Number(formData.maxGuests),
        includedGuests: Number(formData.includedGuests),
        firstBlockPrice: Number(formData.firstBlockPrice),
        extraUnitPrice: Number(formData.extraUnitPrice),
        overnightPrice: Number(formData.overnightPrice),
        dailyPrice: Number(formData.dailyPrice),
        extraGuestPrice: Number(formData.extraGuestPrice),
        imageUrl: imageUrls[0] || "",
        imageUrls,
        description: formData.description,
        descriptionEn: formData.descriptionEn,
        facilityIds: formData.facilityIds,
      });

      showToast("Thêm room type mới thành công", "success");
      setIsCreateOpen(false);
      fetchData();
    } catch (error: unknown) {
      const errMsg = getApiErrorMessage(error, "Không thể tạo loại phòng.");
      showToast(errMsg, "error");
    } finally {
      setIsSaving(false);
    }
  };

  const openEditModal = (type: RoomType) => {
    setUploadingSlots(new Set());
    setSelectedRoomType(type);
    setFormData({
      id: type.id,
      typeName: type.typeName,
      typeNameEn: type.typeNameEn || "",
      maxGuests: String(type.maxGuests || 2),
      includedGuests: String(type.includedGuests ?? 1),
      firstBlockPrice: type.firstBlockPrice != null ? String(type.firstBlockPrice) : "",
      extraUnitPrice: type.extraUnitPrice != null ? String(type.extraUnitPrice) : "",
      overnightPrice: type.overnightPrice != null ? String(type.overnightPrice) : "",
      dailyPrice: type.dailyPrice != null ? String(type.dailyPrice) : "",
      extraGuestPrice: type.extraGuestPrice != null ? String(type.extraGuestPrice) : "50000",
      imageUrl: type.imageUrl || "",
      imageUrls: normalizeImageSlots(type.imageUrls, type.imageUrl),
      description: type.description || "",
      descriptionEn: type.descriptionEn || "",
      facilityIds: type.facilities ? type.facilities.map((f) => f.id) : [],
    });
    setIsEditOpen(true);
  };

  const handleEditSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isUploading || isSaving) {
      showToast(localize("Vui lòng đợi ảnh tải xong.", "Please wait for the image upload to finish."), "info");
      return;
    }
    if (!formData.typeName.trim() || Number(formData.maxGuests) < 1) {
      showToast("Vui lòng điền đầy đủ tên và sức chứa loại phòng", "error");
      return;
    }
    const pricingError = validateRatePlan(formData);
    if (pricingError) {
      showToast(pricingError, "error");
      return;
    }

    setIsSaving(true);
    try {
      const imageUrls = Array.from(new Set(formData.imageUrls.map((image) => image.trim()).filter(Boolean))).slice(0, 3);
      await apiClient.put(`/api/room-types/${formData.id}`, {
        typeName: formData.typeName,
        typeNameEn: formData.typeNameEn,
        maxGuests: Number(formData.maxGuests),
        includedGuests: Number(formData.includedGuests),
        firstBlockPrice: Number(formData.firstBlockPrice),
        extraUnitPrice: Number(formData.extraUnitPrice),
        overnightPrice: Number(formData.overnightPrice),
        dailyPrice: Number(formData.dailyPrice),
        extraGuestPrice: Number(formData.extraGuestPrice),
        imageUrl: imageUrls[0] || "",
        imageUrls,
        description: formData.description,
        descriptionEn: formData.descriptionEn,
        facilityIds: formData.facilityIds,
      });

      showToast("Cập nhật room type thành công", "success");
      setIsEditOpen(false);
      fetchData();
    } catch (error: unknown) {
      const errMsg = getApiErrorMessage(error, "Không thể cập nhật room type.");
      showToast(errMsg, "error");
    } finally {
      setIsSaving(false);
    }
  };

  const openDeleteModal = (type: RoomType) => {
    setSelectedRoomType(type);
    setIsDeleteOpen(true);
  };

  const handleDeleteConfirm = async () => {
    if (!selectedRoomType) return;
    try {
      await apiClient.delete(`/api/room-types/${selectedRoomType.id}`);
      showToast("Xóa loại phòng thành công!", "success");
      setIsDeleteOpen(false);
      fetchData();
    } catch (error: unknown) {
      const errMsg = getApiErrorMessage(error, "Không thể xóa loại phòng.");
      showToast(errMsg, "error");
    }
  };

  const toggleFacility = (facilityId: number) => {
    const current = [...formData.facilityIds];
    const index = current.indexOf(facilityId);
    if (index >= 0) current.splice(index, 1);
    else current.push(facilityId);
    setFormData({ ...formData, facilityIds: current });
  };

  const openStatusModal = (type: RoomType) => {
    setSelectedRoomType(type);
    setStatusReason("");
    setIsStatusOpen(true);
  };

  const handleStatusConfirm = async () => {
    if (!selectedRoomType || isSaving) return;
    const nextActive = !selectedRoomType.active;
    if (!nextActive && !statusReason.trim()) {
      showToast(localize("Vui lòng nhập lý do ngừng hoạt động.", "Please enter a deactivation reason."), "error");
      return;
    }
    setIsSaving(true);
    try {
      await apiClient.patch(`/api/room-types/${selectedRoomType.id}/active`, {
        active: nextActive,
        reason: statusReason.trim() || undefined,
      });
      showToast(nextActive
        ? localize("Đã kích hoạt lại loại phòng.", "Room type reactivated.")
        : localize("Đã ngừng cung cấp loại phòng.", "Room type deactivated."), "success");
      setIsStatusOpen(false);
      await fetchData();
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, localize("Không thể đổi trạng thái loại phòng.", "Could not change room type status.")), "error");
    } finally {
      setIsSaving(false);
    }
  };

  const renderRatePlanFields = () => {
    const fields: Array<{
      key: RateAmountField;
      labelVi: string;
      labelEn: string;
      hintVi: string;
      hintEn: string;
      allowZero?: boolean;
    }> = [
      { key: "firstBlockPrice", labelVi: "Giá 2 giờ đầu *", labelEn: "First 2 hours *", hintVi: "Mức khởi điểm của gói nghỉ giờ", hintEn: "Hourly package starting rate" },
      { key: "extraUnitPrice", labelVi: "Mỗi giờ thêm *", labelEn: "Each extra hour *", hintVi: "Tính sau 2 giờ đầu", hintEn: "Applied after the first 2 hours" },
      { key: "overnightPrice", labelVi: "Giá qua đêm *", labelEn: "Overnight rate *", hintVi: "Mức giá công bố trên thẻ phòng", hintEn: "Published on room cards" },
      { key: "dailyPrice", labelVi: "Giá ngày đêm *", labelEn: "24-hour rate *", hintVi: "Một chu kỳ lưu trú 24 giờ", hintEn: "One 24-hour stay cycle" },
      { key: "extraGuestPrice", labelVi: "Phụ thu khách thêm *", labelEn: "Extra guest surcharge *", hintVi: "Bắt buộc lớn hơn 0 khi sức chứa tối đa cao hơn số khách phù hợp", hintEn: "Must be greater than zero when maximum occupancy exceeds suitable occupancy", allowZero: Number(formData.maxGuests) <= Number(formData.includedGuests) },
    ];

    return (
      <section className="space-y-4 rounded-2xl border border-[#B8944F]/30 bg-[#F8F6F0] p-4 sm:p-5">
        <div className="flex flex-col gap-2 border-b border-[#0F2A43]/10 pb-3 sm:flex-row sm:items-start sm:justify-between">
          <div>
            <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#947333]">{localize("Cấu hình tài chính", "Financial configuration")}</p>
            <h4 className="mt-1 font-serif text-xl font-bold text-[#0F2A43]">{localize("Bảng giá giờ · đêm · ngày", "Hourly · overnight · daily rates")}</h4>
          </div>
          <p className="max-w-sm text-xs leading-5 text-[#66727C]">
            {localize(
              "Thay đổi giá sẽ tạo phiên bản mới. Đơn đã đặt vẫn giữ nguyên bảng giá và số tiền đã chốt.",
              "A price change creates a new version. Existing bookings keep their committed rates and totals.",
            )}
          </p>
        </div>

        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          <div>
            <label htmlFor="room-type-included-guests" className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-[#66727C]">
              {localize("Số khách phù hợp, đã gồm trong giá *", "Suitable guests included in rate *")}
            </label>
            <input
              id="room-type-included-guests"
              type="number"
              min="1"
              max={Math.max(1, Number(formData.maxGuests) || 1)}
              required
              value={formData.includedGuests}
              onChange={(event) => setFormData((current) => ({ ...current, includedGuests: event.target.value }))}
              className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3.5 text-sm font-semibold outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
            />
            <p className="mt-1 text-[11px] text-[#66727C]">{localize("Đây là số khách public hiển thị; khách vượt mức này sẽ phụ thu đến giới hạn tối đa.", "This is the public suitable occupancy; extra guests are charged up to the maximum.")}</p>
          </div>

          {fields.map((field) => (
            <div key={field.key}>
              <label htmlFor={`room-type-${field.key}`} className="mb-1.5 block text-xs font-bold uppercase tracking-wider text-[#66727C]">
                {localize(field.labelVi, field.labelEn)}
              </label>
              <div className="relative">
                <input
                  id={`room-type-${field.key}`}
                  type="number"
                  min={field.allowZero ? "0" : "1"}
                  step="1000"
                  required
                  value={formData[field.key]}
                  onChange={(event) => setFormData((current) => ({ ...current, [field.key]: event.target.value }))}
                  className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3.5 pr-10 text-sm font-semibold tabular-nums outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                />
                <span className="pointer-events-none absolute inset-y-0 right-3 flex items-center text-xs font-bold text-[#80632F]">₫</span>
              </div>
              <p className="mt-1 text-[11px] text-[#66727C]">{localize(field.hintVi, field.hintEn)}</p>
            </div>
          ))}
        </div>
      </section>
    );
  };

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-8 p-4 sm:p-6 lg:p-8">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 pb-4 border-b border-[#0F2A43]/5">
        <div>
          <h1 className="font-serif text-3xl md:text-4xl font-bold tracking-tight text-[#0F2A43] leading-tight">{localize("Quản lý loại phòng", "Room type management")}</h1>
          <p className="text-xs text-[#66727C] mt-1.5 font-bold uppercase tracking-wider">{localize(`${roomTypes.length} loại phòng đã cấu hình tiện nghi và bảng giá`, `${roomTypes.length} room types configured with facilities and rate plans`)}</p>
        </div>
        {isAdmin ? <button
          onClick={openCreateModal}
          className="self-start sm:self-auto px-5 py-2.5 bg-[#0F2A43] hover:bg-[#091E30] text-white font-semibold text-sm rounded-lg shadow-sm transition-all duration-300 flex items-center gap-2"
        >
          <span>+</span> {localize("Thêm loại phòng", "Add room type")}
        </button> : role === "STAFF" ? <span className="rounded-lg border border-[#B8944F]/30 bg-[#F0EADF] px-4 py-2 text-xs font-bold text-[#80632F]">{localize("Chế độ chỉ xem", "Read-only mode")}</span> : null}
      </div>

      <div className="space-y-6">
        <DashboardFilterPanel
          title={localize("Bộ lọc loại phòng", "Room type filters")}
          description={localize("Tìm theo tên, mô tả, tiện nghi; lọc sức chứa và sắp xếp theo giá qua đêm", "Search names, descriptions and facilities; filter capacity and sort by overnight rate")}
          resultCount={filteredRoomTypes.length}
          resultLabel={localize("loại phòng phù hợp", "matching room types")}
          resultNote={sortOrder === "DEFAULT"
            ? localize("theo thứ tự cấu hình", "in configured order")
            : localize("đã áp dụng sắp xếp", "custom sorting applied")}
          hasActiveFilters={Boolean(searchQuery || capacityFilter !== "ALL" || statusFilter !== "ALL" || sortOrder !== "DEFAULT")}
          activeFilterCount={Number(Boolean(searchQuery)) + Number(capacityFilter !== "ALL") + Number(statusFilter !== "ALL") + Number(sortOrder !== "DEFAULT")}
          activeFilterLabel={localize("điều kiện đang dùng", "active conditions")}
          onReset={() => {
            setSearchQuery("");
            setCapacityFilter("ALL");
            setStatusFilter("ALL");
            setSortOrder("DEFAULT");
          }}
          resetLabel={localize("Đặt lại tìm kiếm", "Reset search")}
          actions={(
            <>
              <FilterQuickButton active={sortOrder === "PRICE_ASC"} onClick={() => setSortOrder((current) => current === "PRICE_ASC" ? "DEFAULT" : "PRICE_ASC")}>
                {localize("Giá thấp trước", "Lowest price")}
              </FilterQuickButton>
              <FilterQuickButton active={sortOrder === "CAPACITY_DESC"} onClick={() => setSortOrder((current) => current === "CAPACITY_DESC" ? "DEFAULT" : "CAPACITY_DESC")}>
                {localize("Sức chứa lớn", "Largest capacity")}
              </FilterQuickButton>
            </>
          )}
        >
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-[minmax(0,2fr)_repeat(3,minmax(11rem,1fr))]">
            <DashboardSearchField
              id="room-type-search"
              label={localize("Tìm kiếm", "Search")}
              value={searchQuery}
              onChange={setSearchQuery}
              placeholder={localize("Tên loại phòng, mô tả hoặc tiện nghi...", "Room type, description or facility...")}
              clearLabel={localize("Xóa từ khóa", "Clear search")}
            />
            <DashboardSelectField id="room-type-capacity" label={localize("Sức chứa", "Capacity")} value={capacityFilter} onChange={(event) => setCapacityFilter(event.target.value as CapacityFilter)}>
              <option value="ALL">{localize("Tất cả sức chứa", "All capacities")}</option>
              <option value="ONE_TWO">{localize("1–2 khách", "1–2 guests")}</option>
              <option value="THREE_FOUR">{localize("3–4 khách", "3–4 guests")}</option>
              <option value="FIVE_PLUS">{localize("Từ 5 khách", "5+ guests")}</option>
            </DashboardSelectField>
            <DashboardSelectField id="room-type-status" label={localize("Trạng thái", "Status")} value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as StatusFilter)}>
              <option value="ALL">{localize("Tất cả trạng thái", "All statuses")}</option>
              <option value="ACTIVE">{localize("Đang hoạt động", "Active")}</option>
              <option value="INACTIVE">{localize("Ngừng hoạt động", "Inactive")}</option>
            </DashboardSelectField>
            <DashboardSelectField id="room-type-sort" label={localize("Sắp xếp", "Sort by")} value={sortOrder} onChange={(event) => setSortOrder(event.target.value as RoomTypeSort)}>
              <option value="DEFAULT">{localize("Thứ tự mặc định", "Default order")}</option>
              <option value="PRICE_ASC">{localize("Giá tăng dần", "Price: low to high")}</option>
              <option value="PRICE_DESC">{localize("Giá giảm dần", "Price: high to low")}</option>
              <option value="CAPACITY_DESC">{localize("Sức chứa giảm dần", "Capacity: high to low")}</option>
            </DashboardSelectField>
          </div>
        </DashboardFilterPanel>

        {isLoading ? (
          <div className="grid grid-cols-1 gap-8 md:grid-cols-2 lg:grid-cols-3" role="status" aria-label={localize("Đang tải danh sách loại phòng", "Loading room types")}>
            {[0, 1, 2, 3, 4, 5].map((item) => <div key={item} className="overflow-hidden rounded-2xl border border-[#0F2A43]/10 bg-white"><div className="h-48 animate-pulse bg-[#E5E9ED]" /><div className="space-y-3 p-5"><div className="h-7 w-2/3 animate-pulse rounded bg-[#E5E9ED]" /><div className="h-4 w-full animate-pulse rounded bg-[#E5E9ED]" /><div className="h-11 w-1/2 animate-pulse rounded bg-[#E5E9ED]" /></div></div>)}
          </div>
        ) : filteredRoomTypes.length === 0 ? (
          <div className="bg-white text-center py-12 border-2 border-dashed border-[#0F2A43]/10 rounded-xl text-[#66727C] font-semibold text-sm">
            {localize("Không tìm thấy loại phòng.", "No room types found.")}
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-8">
            {filteredRoomTypes.map((type) => (
              <div key={type.id} className={`bg-white border rounded-2xl overflow-hidden shadow-sm flex flex-col justify-between transition-all duration-300 group ${type.active ? "border-[#0F2A43]/10 hover:border-[#B8944F]/40" : "border-slate-300 opacity-80"}`}>
                <div className="relative h-48 bg-gray-100 overflow-hidden">
                  {(type.imageUrls?.[0] || type.imageUrl) ? (
                    <Image
                      src={resolveMediaSource(type.imageUrls?.[0] || type.imageUrl || "")}
                      alt={localize(type.typeName, type.typeNameEn)}
                      fill
                      sizes="(min-width: 1024px) 33vw, (min-width: 768px) 50vw, 100vw"
                      className="object-cover transition-transform duration-500 group-hover:scale-105"
                    />
                  ) : (
                    <div className="flex h-full w-full items-center justify-center text-sm font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</div>
                  )}
                  <div className="absolute top-4 right-4 bg-[#0F2A43] text-[#B8944F] font-serif font-bold text-lg px-3 py-1.5 rounded-lg shadow-md">
                    {type.overnightPrice != null
                      ? Number(type.overnightPrice).toLocaleString(localeTag)
                      : "—"} đ <span className="text-[10px] uppercase font-sans font-semibold tracking-wider text-white/70">{localize("qua đêm", "overnight")}</span>
                  </div>
                </div>

                <div className="p-6 flex-grow space-y-4">
                  <div>
                    <h4 className="font-serif text-xl font-bold text-[#0F2A43]">{localize(type.typeName, type.typeNameEn)}</h4>
                    <p className="text-xs text-gray-400 mt-2 font-light leading-relaxed">
                      {localize(type.description, type.descriptionEn) || localize("Chưa có mô tả.", "No description provided.")}
                    </p>
                  </div>
                  <span className={`absolute left-4 top-4 rounded-full border px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider ${type.active ? "border-emerald-200 bg-emerald-50 text-emerald-700" : "border-slate-300 bg-slate-100 text-slate-600"}`}>
                    {type.active ? localize("Đang hoạt động", "Active") : localize("Ngừng hoạt động", "Inactive")}
                  </span>

                  {type.pricingAvailable ? (
                    <div className="space-y-2">
                    <dl className="grid grid-cols-3 gap-2 rounded-xl border border-[#0F2A43]/10 bg-[#F8F6F0] p-3 text-center">
                      <div>
                        <dt className="text-[9px] font-bold uppercase tracking-wider text-[#66727C]">{localize("2 giờ đầu", "First 2h")}</dt>
                        <dd className="mt-1 text-xs font-extrabold tabular-nums text-[#0F2A43]">{Number(type.firstBlockPrice || 0).toLocaleString(localeTag)} đ</dd>
                      </div>
                      <div className="border-x border-[#0F2A43]/10 px-1">
                        <dt className="text-[9px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Qua đêm", "Overnight")}</dt>
                        <dd className="mt-1 text-xs font-extrabold tabular-nums text-[#80632F]">{Number(type.overnightPrice || 0).toLocaleString(localeTag)} đ</dd>
                      </div>
                      <div>
                        <dt className="text-[9px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Ngày đêm", "24 hours")}</dt>
                        <dd className="mt-1 text-xs font-extrabold tabular-nums text-[#0F2A43]">{Number(type.dailyPrice || 0).toLocaleString(localeTag)} đ</dd>
                      </div>
                    </dl>
                    <p className="rounded-lg bg-[#E8EFEA] px-3 py-2 text-[11px] font-semibold leading-5 text-[#315746]">
                      {localize(
                        `Phù hợp ${type.includedGuests ?? type.maxGuests} khách · tối đa ${type.maxGuests} khách/phòng${type.maxGuests > Number(type.includedGuests ?? type.maxGuests) ? ` · phụ thu ${Number(type.extraGuestPrice || 0).toLocaleString(localeTag)} đ/khách/mỗi chu kỳ lưu trú` : ""}`,
                        `Suitable for ${type.includedGuests ?? type.maxGuests} guests · max ${type.maxGuests} per room${type.maxGuests > Number(type.includedGuests ?? type.maxGuests) ? ` · ${Number(type.extraGuestPrice || 0).toLocaleString(localeTag)} VND/extra guest/cycle` : ""}`,
                      )}
                    </p>
                    </div>
                  ) : (
                    <p className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-xs font-semibold text-rose-700">
                      {localize("Chưa có bảng giá đang hiệu lực", "No effective rate plan")}
                    </p>
                  )}

                  {type.facilities && type.facilities.length > 0 && (
                    <div className="space-y-1.5">
                      <span className="block text-[9px] tracking-wider uppercase font-bold text-[#66727C]">{localize("Tiện nghi đi kèm", "Included amenities")}</span>
                      <div className="flex flex-wrap gap-1">
                        {type.facilities.map((fac) => (
                          <span key={fac.id} className="text-[9px] font-bold px-2 py-0.5 bg-[#F0EADF] text-[#80632F] rounded-md border border-[#F0EADF]">
                            {localize(fac.facilityName, fac.facilityNameEn)}
                          </span>
                        ))}
                      </div>
                    </div>
                  )}
                </div>

                {isAdmin && <div className="px-6 py-4 bg-[#F1F0EA] border-t border-[#0F2A43]/5 flex flex-wrap justify-end gap-2">
                  <button
                    onClick={() => openEditModal(type)}
                    className="px-4 py-2 border border-gray-200 hover:border-[#B8944F] text-gray-500 hover:text-[#B8944F] text-xs font-bold rounded-lg transition-colors"
                  >
                    {localize("Sửa loại phòng", "Edit type")}
                  </button>
                  <button
                    onClick={() => openStatusModal(type)}
                    className={`px-4 py-2 border text-xs font-bold rounded-lg transition-colors ${type.active ? "border-amber-300 text-amber-800 hover:bg-amber-50" : "border-emerald-300 text-emerald-700 hover:bg-emerald-50"}`}
                  >
                    {type.active ? localize("Ngừng hoạt động", "Deactivate") : localize("Kích hoạt", "Reactivate")}
                  </button>
                  {!type.active && <button
                    onClick={() => openDeleteModal(type)}
                    className="px-4 py-2 border border-red-200 hover:bg-red-50 text-red-600 text-xs font-bold rounded-lg transition-colors"
                  >
                    {localize("Xóa vĩnh viễn", "Delete permanently")}
                  </button>}
                </div>}
              </div>
            ))}
          </div>
        )}
      </div>

      {isAdmin && (
        <ViewportModal
          open={isCreateOpen}
          onClose={() => setIsCreateOpen(false)}
          labelledBy="create-room-type-title"
          busy={isSaving || isUploading}
          panelClassName="max-w-4xl"
        >
          <div className="min-h-0 w-full space-y-6 overflow-y-auto p-5 sm:p-8">
            <h3 id="create-room-type-title" className="text-xl font-bold text-[#0F2A43]">{localize("Thêm loại phòng", "Add room type")}</h3>
            <form onSubmit={handleCreateSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Tên loại phòng (VI) *", "Room type name (VI) *")}</label>
                <input
                  data-modal-autofocus
                  type="text"
                  required
                  value={formData.typeName}
                  onChange={(e) => setFormData({ ...formData, typeName: e.target.value })}
                  className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm"
                  placeholder="Ví dụ: Phòng Tổng thống"
                />
              </div>
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Tên loại phòng (EN)", "Room type name (EN)")}</label>
                <input type="text" value={formData.typeNameEn} onChange={(e) => setFormData({ ...formData, typeNameEn: e.target.value })} className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm" placeholder="e.g. Presidential Suite" />
              </div>

              <div className="max-w-sm">
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Sức chứa tối đa *", "Maximum occupancy *")}</label>
                  <input
                    type="number"
                    min="1"
                    max="20"
                    required
                    value={formData.maxGuests}
                    onChange={(e) => setFormData({ ...formData, maxGuests: e.target.value })}
                    className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm"
                    aria-label="Sức chứa tối đa mỗi phòng"
                  />
                  <p className="mt-1 text-[11px] text-[#66727C]">{localize("Giới hạn cứng sau khi tính cả khách phụ thu; cấu hình riêng cho từng loại phòng.", "Hard limit including extra guests; configured per room type.")}</p>
                </div>
              </div>

              {renderRatePlanFields()}

              <div className="grid gap-3 rounded-xl border border-[#0F2A43]/10 bg-[#F8F6F0] p-3 lg:grid-cols-3">
                {formData.imageUrls.map((value, index) => (
                  <ImageUploadField
                    key={`create-room-type-image-${index}`}
                    id={`create-room-type-image-${index}`}
                    folder="ROOM_TYPES"
                    value={value}
                    label={index === 0
                      ? localize("Ảnh đại diện", "Cover image")
                      : localize(`Ảnh chi tiết ${index}`, `Detail image ${index}`)}
                    alt={localize(
                      `Ảnh ${index + 1} của loại phòng ${formData.typeName || "mới"}`,
                      `Image ${index + 1} of ${formData.typeNameEn || formData.typeName || "new room type"}`,
                    )}
                    description={localize("Ảnh ngang · tối đa 5 MB.", "Landscape image · up to 5 MB.")}
                    onUploadingChange={(uploading) => setSlotUploading(index, uploading)}
                    onUploaded={(image) => setFormData((current) => {
                      const imageUrls = [...current.imageUrls];
                      imageUrls[index] = image.url;
                      return {
                        ...current,
                        imageUrl: index === 0 ? image.url : current.imageUrl,
                        imageUrls,
                      };
                    })}
                  />
                ))}
              </div>

              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Mô tả (VI)", "Description (VI)")}</label>
                <textarea
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm h-20 resize-none"
                  placeholder="Mô tả thiết kế và tiện nghi của loại phòng..."
                />
              </div>
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Mô tả (EN)", "Description (EN)")}</label>
                <textarea value={formData.descriptionEn} onChange={(e) => setFormData({ ...formData, descriptionEn: e.target.value })} className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm h-20 resize-none" placeholder="Describe room type amenities and design..." />
              </div>

              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-2">{localize("Gán tiện nghi", "Assign amenities")}</label>
                <div className="grid grid-cols-2 gap-2 border border-gray-100 p-4 rounded-lg bg-gray-50/50 max-h-36 overflow-y-auto">
                  {facilities.map((fac) => (
                    <label key={fac.id} className="flex items-center gap-2 text-xs font-medium text-text-dark cursor-pointer select-none">
                      <input
                        type="checkbox"
                        checked={formData.facilityIds.includes(fac.id)}
                        onChange={() => toggleFacility(fac.id)}
                        className="rounded border-gray-300 text-accent-gold focus:ring-accent-gold"
                      />
                      <span>{localize(fac.facilityName, fac.facilityNameEn)}</span>
                    </label>
                  ))}
                </div>
              </div>

              <div className="flex justify-end gap-3 pt-4">
                <button
                  type="button"
                  onClick={() => setIsCreateOpen(false)}
                  disabled={isSaving}
                  className="px-5 py-2.5 border border-gray-200 hover:bg-gray-50 text-[#66727C] font-semibold text-sm rounded-lg"
                >
                  {localize("Hủy", "Cancel")}
                </button>
                <button
                  type="submit"
                  disabled={isSaving || isUploading}
                  className="px-5 py-2.5 bg-[#0F2A43] hover:bg-[#091E30] text-white font-semibold text-sm rounded-lg disabled:cursor-not-allowed disabled:opacity-55"
                >
                  {isSaving
                    ? localize("Đang tạo...", "Creating...")
                    : isUploading
                      ? localize("Đang tải ảnh...", "Uploading image...")
                      : localize("Tạo loại phòng", "Create")}
                </button>
              </div>
            </form>
          </div>
        </ViewportModal>
      )}

      {isAdmin && (
        <ViewportModal
          open={isEditOpen}
          onClose={() => setIsEditOpen(false)}
          labelledBy="edit-room-type-title"
          busy={isSaving || isUploading}
          panelClassName="max-w-4xl"
        >
          <div className="min-h-0 w-full space-y-6 overflow-y-auto p-5 sm:p-8">
            <h3 id="edit-room-type-title" className="text-xl font-bold text-[#0F2A43]">{localize("Chỉnh sửa loại phòng", "Edit room type")}</h3>
            <form onSubmit={handleEditSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Tên loại phòng (VI) *", "Room type name (VI) *")}</label>
                <input
                  data-modal-autofocus
                  type="text"
                  required
                  value={formData.typeName}
                  onChange={(e) => setFormData({ ...formData, typeName: e.target.value })}
                  className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm"
                />
              </div>
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Tên loại phòng (EN)", "Room type name (EN)")}</label>
                <input type="text" value={formData.typeNameEn} onChange={(e) => setFormData({ ...formData, typeNameEn: e.target.value })} className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm" />
              </div>

              <div className="max-w-sm">
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Sức chứa tối đa *", "Maximum occupancy *")}</label>
                  <input
                    type="number"
                    min="1"
                    max="20"
                    required
                    value={formData.maxGuests}
                    onChange={(e) => setFormData({ ...formData, maxGuests: e.target.value })}
                    className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm"
                    aria-label="Sức chứa tối đa mỗi phòng"
                  />
                  <p className="mt-1 text-[11px] text-[#66727C]">{localize("Giới hạn cứng sau khi tính cả khách phụ thu; cấu hình riêng cho từng loại phòng.", "Hard limit including extra guests; configured per room type.")}</p>
                </div>
              </div>

              {renderRatePlanFields()}

              <div className="grid gap-3 rounded-xl border border-[#0F2A43]/10 bg-[#F8F6F0] p-3 lg:grid-cols-3">
                {formData.imageUrls.map((value, index) => (
                  <ImageUploadField
                    key={`edit-room-type-image-${index}`}
                    id={`edit-room-type-image-${index}`}
                    folder="ROOM_TYPES"
                    value={value}
                    label={index === 0
                      ? localize("Ảnh đại diện", "Cover image")
                      : localize(`Ảnh chi tiết ${index}`, `Detail image ${index}`)}
                    alt={localize(
                      `Ảnh ${index + 1} của loại phòng ${formData.typeName}`,
                      `Image ${index + 1} of ${formData.typeNameEn || formData.typeName}`,
                    )}
                    description={localize("Chọn ảnh mới để thay ảnh hiện tại · tối đa 5 MB.", "Choose a replacement image · up to 5 MB.")}
                    onUploadingChange={(uploading) => setSlotUploading(index, uploading)}
                    onUploaded={(image) => setFormData((current) => {
                      const imageUrls = [...current.imageUrls];
                      imageUrls[index] = image.url;
                      return {
                        ...current,
                        imageUrl: index === 0 ? image.url : current.imageUrl,
                        imageUrls,
                      };
                    })}
                  />
                ))}
              </div>

              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Mô tả (VI)", "Description (VI)")}</label>
                <textarea
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm h-20 resize-none"
                />
              </div>
              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Mô tả (EN)", "Description (EN)")}</label>
                <textarea value={formData.descriptionEn} onChange={(e) => setFormData({ ...formData, descriptionEn: e.target.value })} className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm h-20 resize-none" />
              </div>

              <div>
                <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-2">{localize("Gán tiện nghi", "Assign amenities")}</label>
                <div className="grid grid-cols-2 gap-2 border border-gray-100 p-4 rounded-lg bg-gray-50/50 max-h-36 overflow-y-auto">
                  {facilities.map((fac) => (
                    <label key={fac.id} className="flex items-center gap-2 text-xs font-medium text-text-dark cursor-pointer select-none">
                      <input
                        type="checkbox"
                        checked={formData.facilityIds.includes(fac.id)}
                        onChange={() => toggleFacility(fac.id)}
                        className="rounded border-gray-300 text-accent-gold focus:ring-accent-gold"
                      />
                      <span>{localize(fac.facilityName, fac.facilityNameEn)}</span>
                    </label>
                  ))}
                </div>
              </div>

              <div className="flex justify-end gap-3 pt-4">
                <button
                  type="button"
                  onClick={() => setIsEditOpen(false)}
                  disabled={isSaving}
                  className="px-5 py-2.5 border border-gray-200 hover:bg-gray-50 text-[#66727C] font-semibold text-sm rounded-lg"
                >
                  {localize("Hủy", "Cancel")}
                </button>
                <button
                  type="submit"
                  disabled={isSaving || isUploading}
                  className="px-5 py-2.5 bg-[#0F2A43] hover:bg-[#091E30] text-white font-semibold text-sm rounded-lg disabled:cursor-not-allowed disabled:opacity-55"
                >
                  {isSaving
                    ? localize("Đang lưu...", "Saving...")
                    : isUploading
                      ? localize("Đang tải ảnh...", "Uploading image...")
                      : localize("Lưu thay đổi", "Save changes")}
                </button>
              </div>
            </form>
          </div>
        </ViewportModal>
      )}

      {isAdmin && selectedRoomType && (
        <ViewportModal
          open={isStatusOpen}
          onClose={() => setIsStatusOpen(false)}
          labelledBy="room-type-status-title"
          busy={isSaving}
          panelClassName="max-w-md"
        >
          <div className="min-h-0 w-full space-y-5 overflow-y-auto p-6 sm:p-7">
            <div>
              <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#947333]">{localize("Trạng thái kinh doanh", "Sales status")}</p>
              <h3 id="room-type-status-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">
                {selectedRoomType.active
                  ? localize("Ngừng cung cấp loại phòng", "Deactivate room type")
                  : localize("Kích hoạt lại loại phòng", "Reactivate room type")}
              </h3>
              <p className="mt-2 text-sm leading-6 text-[#66727C]">
                {selectedRoomType.active
                  ? localize("Loại phòng sẽ ẩn khỏi trang khách và không thể dùng cho báo giá hay đơn mới. Dữ liệu đơn cũ vẫn được giữ nguyên.", "The room type will be hidden from guests and blocked from new quotes or bookings. Historical reservations stay intact.")
                  : localize("Hệ thống chỉ kích hoạt khi loại phòng có đúng một bảng giá giờ, đêm và ngày đang hiệu lực.", "Reactivation is allowed only when exactly one hourly, overnight and daily rate plan is effective.")}
              </p>
            </div>
            {selectedRoomType.active && (
              <label className="grid gap-1.5 text-xs font-bold uppercase tracking-wider text-[#66727C]">
                {localize("Lý do ngừng hoạt động", "Deactivation reason")} *
                <textarea
                  data-modal-autofocus
                  rows={3}
                  maxLength={500}
                  value={statusReason}
                  onChange={(event) => setStatusReason(event.target.value)}
                  placeholder={localize("Ví dụ: tạm dừng để bảo trì toàn bộ hạng phòng", "For example: temporarily unavailable for maintenance")}
                  className="resize-none rounded-xl border border-[#0F2A43]/15 px-3.5 py-3 text-sm font-medium normal-case outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/20"
                />
              </label>
            )}
            <div className="flex justify-end gap-3 border-t border-[#0F2A43]/10 pt-4">
              <button type="button" disabled={isSaving} onClick={() => setIsStatusOpen(false)} className="min-h-11 rounded-lg border border-[#0F2A43]/15 px-5 text-sm font-semibold text-[#66727C] hover:bg-[#F1F0EA] disabled:opacity-50">
                {localize("Hủy", "Cancel")}
              </button>
              <button type="button" disabled={isSaving} onClick={handleStatusConfirm} className={`min-h-11 rounded-lg px-5 text-sm font-bold text-white disabled:cursor-wait disabled:opacity-55 ${selectedRoomType.active ? "bg-amber-700 hover:bg-amber-800" : "bg-emerald-700 hover:bg-emerald-800"}`}>
                {isSaving
                  ? localize("Đang lưu...", "Saving...")
                  : selectedRoomType.active
                    ? localize("Xác nhận ngừng", "Confirm deactivation")
                    : localize("Kích hoạt", "Reactivate")}
              </button>
            </div>
          </div>
        </ViewportModal>
      )}

      {isAdmin && selectedRoomType && (
        <ViewportModal
          open={isDeleteOpen}
          onClose={() => setIsDeleteOpen(false)}
          labelledBy="delete-room-type-title"
          busy={isSaving}
          panelClassName="max-w-sm"
        >
          <div className="min-h-0 w-full space-y-6 overflow-y-auto p-6 text-center sm:p-8">
            <div className="mx-auto flex h-16 w-16 items-center justify-center rounded-full bg-red-50 text-red-600">
              <svg aria-hidden="true" className="h-8 w-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><path d="M12 9v4"/><path d="M12 17h.01"/></svg>
            </div>
            <div className="space-y-2">
              <h3 id="delete-room-type-title" className="text-xl font-bold text-[#0F2A43]">{localize("Xóa loại phòng", "Delete room type")}</h3>
              <p className="text-sm text-[#66727C]">
                {localize("Bạn có chắc muốn xóa", "Are you sure you want to delete")} <strong>{localize(selectedRoomType.typeName, selectedRoomType.typeNameEn)}</strong>? {localize("Chỉ loại phòng chưa từng có phòng vật lý, đơn đặt, đánh giá hoặc báo giá mới được xóa. Nếu đã phát sinh lịch sử, hệ thống sẽ giữ ở trạng thái ngừng hoạt động.", "Only a room type never used by a physical room, reservation, review or quote can be deleted. Otherwise it remains inactive to preserve history.")}
              </p>
            </div>
            <div className="flex gap-3 pt-2">
              <button
                onClick={() => setIsDeleteOpen(false)}
                className="flex-1 py-3 border border-gray-200 hover:bg-gray-50 text-[#66727C] font-semibold text-sm rounded-lg"
              >
                {localize("Hủy", "Cancel")}
              </button>
              <button
                onClick={handleDeleteConfirm}
                className="flex-1 py-3 bg-red-600 hover:bg-red-700 text-white font-semibold text-sm rounded-lg shadow-md"
              >
                {localize("Xóa", "Delete")}
              </button>
            </div>
          </div>
        </ViewportModal>
      )}

      {toast && (
        <Toast
          message={toast.message}
          type={toast.type}
          onClose={() => setToast(null)}
        />
      )}
    </div>
  );
}
