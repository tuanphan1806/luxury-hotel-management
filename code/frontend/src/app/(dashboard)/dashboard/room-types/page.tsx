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
import { getPublicRoomTypes, invalidatePublicRoomTypes } from "@/lib/public-catalog";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

type CapacityFilter = "ALL" | "ONE_TWO" | "THREE_FOUR" | "FIVE_PLUS";
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
  typeName: string;
  typeNameEn?: string;
  description: string;
  descriptionEn?: string;
  price: number;
  maxGuests: number;
  imageUrl: string;
  facilities: Facility[];
}

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
  const [sortOrder, setSortOrder] = useState<RoomTypeSort>("DEFAULT");
  const [isUploading, setIsUploading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isEditOpen, setIsEditOpen] = useState(false);
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);

  const [formData, setFormData] = useState({
    id: 0,
    typeName: "",
    typeNameEn: "",
    price: "",
    maxGuests: "2",
    imageUrl: "",
    description: "",
    descriptionEn: "",
    facilityIds: [] as number[],
  });

  const [selectedRoomType, setSelectedRoomType] = useState<RoomType | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const showToast = useCallback((message: string, type: "success" | "error" | "info") => {
    setToast({ message, type });
  }, []);

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    try {
      const [typesRes, facRes] = await Promise.all([
        getPublicRoomTypes<RoomType>(),
        cachedGet("/api/facilities"),
      ]);

      setRoomTypes(typesRes);
      if (facRes.data && facRes.data.data) {
        setFacilities(facRes.data.data);
      }
    } catch {
      showToast("Không thể tải danh sách room type từ backend", "error");
    } finally {
      setIsLoading(false);
    }
  }, [showToast]);

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
      return matchesSearch && matchesCapacity;
    });

    if (sortOrder === "DEFAULT") return matched;
    return [...matched].sort((left, right) => {
      if (sortOrder === "PRICE_ASC") return Number(left.price || 0) - Number(right.price || 0);
      if (sortOrder === "PRICE_DESC") return Number(right.price || 0) - Number(left.price || 0);
      return Number(right.maxGuests || 0) - Number(left.maxGuests || 0);
    });
  }, [capacityFilter, roomTypes, searchQuery, sortOrder]);

  const openCreateModal = () => {
    setFormData({
      id: 0,
      typeName: "",
      typeNameEn: "",
      price: "",
      maxGuests: "2",
      imageUrl: "",
      description: "",
      descriptionEn: "",
      facilityIds: [],
    });
    setIsCreateOpen(true);
  };

  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isUploading || isSaving) {
      showToast(localize("Vui lòng đợi ảnh tải xong.", "Please wait for the image upload to finish."), "info");
      return;
    }
    if (!formData.typeName.trim() || !formData.price || Number(formData.maxGuests) < 1) {
      showToast("Vui lòng nhập tên room type và base price", "error");
      return;
    }

    setIsSaving(true);
    try {
      await apiClient.post("/api/room-types", {
        typeName: formData.typeName,
        typeNameEn: formData.typeNameEn,
        price: Number(formData.price),
        maxGuests: Number(formData.maxGuests),
        imageUrl: formData.imageUrl,
        description: formData.description,
        descriptionEn: formData.descriptionEn,
        facilityIds: formData.facilityIds,
      });

      showToast("Thêm room type mới thành công", "success");
      setIsCreateOpen(false);
      invalidatePublicRoomTypes();
      fetchData();
    } catch (error: unknown) {
      const errMsg = getApiErrorMessage(error, "Không thể tạo loại phòng.");
      showToast(errMsg, "error");
    } finally {
      setIsSaving(false);
    }
  };

  const openEditModal = (type: RoomType) => {
    setSelectedRoomType(type);
    setFormData({
      id: type.id,
      typeName: type.typeName,
      typeNameEn: type.typeNameEn || "",
      price: String(type.price),
      maxGuests: String(type.maxGuests || 2),
      imageUrl: type.imageUrl || "",
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
    if (!formData.typeName.trim() || !formData.price || Number(formData.maxGuests) < 1) {
      showToast("Vui lòng điền đầy đủ thông tin loại phòng và giá", "error");
      return;
    }

    setIsSaving(true);
    try {
      await apiClient.put(`/api/room-types/${formData.id}`, {
        typeName: formData.typeName,
        typeNameEn: formData.typeNameEn,
        price: Number(formData.price),
        maxGuests: Number(formData.maxGuests),
        imageUrl: formData.imageUrl,
        description: formData.description,
        descriptionEn: formData.descriptionEn,
        facilityIds: formData.facilityIds,
      });

      showToast("Cập nhật room type thành công", "success");
      setIsEditOpen(false);
      invalidatePublicRoomTypes();
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
      invalidatePublicRoomTypes();
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

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-8 p-4 sm:p-6 lg:p-8">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 pb-4 border-b border-[#0F2A43]/5">
        <div>
          <h1 className="font-serif text-3xl md:text-4xl font-bold tracking-tight text-[#0F2A43] leading-tight">{localize("Quản lý loại phòng", "Room type management")}</h1>
          <p className="text-xs text-[#66727C] mt-1.5 font-bold uppercase tracking-wider">{localize(`${roomTypes.length} loại phòng đã cấu hình tiện nghi và giá cơ bản`, `${roomTypes.length} room types configured with facilities and base prices`)}</p>
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
          description={localize("Tìm theo tên, mô tả, tiện nghi; lọc sức chứa và sắp xếp mức giá", "Search names, descriptions and facilities; filter capacity and sort pricing")}
          resultCount={filteredRoomTypes.length}
          resultLabel={localize("loại phòng phù hợp", "matching room types")}
          resultNote={sortOrder === "DEFAULT"
            ? localize("theo thứ tự cấu hình", "in configured order")
            : localize("đã áp dụng sắp xếp", "custom sorting applied")}
          hasActiveFilters={Boolean(searchQuery || capacityFilter !== "ALL" || sortOrder !== "DEFAULT")}
          activeFilterCount={Number(Boolean(searchQuery)) + Number(capacityFilter !== "ALL") + Number(sortOrder !== "DEFAULT")}
          activeFilterLabel={localize("điều kiện đang dùng", "active conditions")}
          onReset={() => {
            setSearchQuery("");
            setCapacityFilter("ALL");
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
          <div className="grid gap-4 md:grid-cols-[minmax(0,2fr)_minmax(12rem,1fr)_minmax(12rem,1fr)]">
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
              <div key={type.id} className="bg-white border border-[#0F2A43]/10 rounded-2xl overflow-hidden shadow-sm flex flex-col justify-between hover:border-[#B8944F]/40 transition-all duration-300 group">
                <div className="relative h-48 bg-gray-100 overflow-hidden">
                  {type.imageUrl ? (
                    <Image
                      src={resolveMediaSource(type.imageUrl)}
                      alt={localize(type.typeName, type.typeNameEn)}
                      fill
                      sizes="(min-width: 1024px) 33vw, (min-width: 768px) 50vw, 100vw"
                      className="object-cover transition-transform duration-500 group-hover:scale-105"
                    />
                  ) : (
                    <div className="flex h-full w-full items-center justify-center text-sm font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</div>
                  )}
                  <div className="absolute top-4 right-4 bg-[#0F2A43] text-[#B8944F] font-serif font-bold text-lg px-3 py-1.5 rounded-lg shadow-md">
                    {Number(type.price || 0).toLocaleString(localeTag)} đ <span className="text-[10px] uppercase font-sans font-semibold tracking-wider text-white/70">{localize("cơ bản", "base")}</span>
                  </div>
                </div>

                <div className="p-6 flex-grow space-y-4">
                  <div>
                    <h4 className="font-serif text-xl font-bold text-[#0F2A43]">{localize(type.typeName, type.typeNameEn)}</h4>
                    <p className="text-xs text-gray-400 mt-2 font-light leading-relaxed">
                      {localize(type.description, type.descriptionEn) || localize("Chưa có mô tả.", "No description provided.")}
                    </p>
                  </div>

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

                {isAdmin && <div className="px-6 py-4 bg-[#F1F0EA] border-t border-[#0F2A43]/5 flex justify-end gap-3">
                  <button
                    onClick={() => openEditModal(type)}
                    className="px-4 py-2 border border-gray-200 hover:border-[#B8944F] text-gray-500 hover:text-[#B8944F] text-xs font-bold rounded-lg transition-colors"
                  >
                    {localize("Sửa loại phòng", "Edit type")}
                  </button>
                  <button
                    onClick={() => openDeleteModal(type)}
                    className="px-4 py-2 border border-red-200 hover:bg-red-50 text-red-600 text-xs font-bold rounded-lg transition-colors"
                  >
                    {localize("Xóa", "Delete")}
                  </button>
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
          panelClassName="max-w-lg"
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

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Giá cơ bản *", "Base price *")}</label>
                  <input
                    type="number"
                    min="1"
                    required
                    value={formData.price}
                    onChange={(e) => setFormData({ ...formData, price: e.target.value })}
                    className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm"
                    placeholder="e.g. 350"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Sức chứa *", "Capacity *")}</label>
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
                </div>

              </div>

              <ImageUploadField
                id="create-room-type-image"
                folder="ROOM_TYPES"
                value={formData.imageUrl}
                label={localize("Ảnh đại diện loại phòng", "Room type cover image")}
                alt={localize(`Ảnh xem trước loại phòng ${formData.typeName || "mới"}`, `Preview of ${formData.typeNameEn || formData.typeName || "new room type"}`)}
                description={localize("Ảnh ngang JPEG, PNG hoặc WebP · tối đa 5 MB.", "Landscape JPEG, PNG or WebP · up to 5 MB.")}
                onUploadingChange={setIsUploading}
                onUploaded={(image) => setFormData((current) => ({ ...current, imageUrl: image.url }))}
              />

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
          panelClassName="max-w-lg"
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

              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Giá cơ bản *", "Base price *")}</label>
                  <input
                    type="number"
                    min="1"
                    required
                    value={formData.price}
                    onChange={(e) => setFormData({ ...formData, price: e.target.value })}
                    className="w-full px-4 py-2.5 rounded-lg border border-gray-200 focus:outline-none focus:ring-2 focus:ring-accent-gold/45 text-sm"
                  />
                </div>

                <div>
                  <label className="block text-xs font-bold uppercase tracking-wider text-[#66727C] mb-1.5">{localize("Sức chứa *", "Capacity *")}</label>
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
                </div>

              </div>

              <ImageUploadField
                id="edit-room-type-image"
                folder="ROOM_TYPES"
                value={formData.imageUrl}
                label={localize("Ảnh đại diện loại phòng", "Room type cover image")}
                alt={localize(`Ảnh xem trước loại phòng ${formData.typeName}`, `Preview of ${formData.typeNameEn || formData.typeName}`)}
                description={localize("Chọn ảnh mới để thay ảnh hiện tại · JPEG, PNG hoặc WebP · tối đa 5 MB.", "Choose a new image to replace the current one · JPEG, PNG or WebP · up to 5 MB.")}
                onUploadingChange={setIsUploading}
                onUploaded={(image) => setFormData((current) => ({ ...current, imageUrl: image.url }))}
              />

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
                {localize("Bạn có chắc muốn xóa", "Are you sure you want to delete")} <strong>{localize(selectedRoomType.typeName, selectedRoomType.typeNameEn)}</strong>? {localize("Thao tác này sẽ xóa vĩnh viễn cấu hình loại phòng.", "This permanently deletes the room type mapping.")}
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
