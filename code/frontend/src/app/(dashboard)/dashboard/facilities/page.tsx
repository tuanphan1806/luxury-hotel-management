"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { apiClient, cachedGet, getApiErrorMessage } from "@/lib/api";
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

type FacilityImageFilter = "ALL" | "WITH_IMAGE" | "WITHOUT_IMAGE";

interface FacilityItem {
  id: number;
  facilityName: string;
  facilityNameEn?: string;
  type?: string;
  description?: string;
  descriptionEn?: string;
  imageUrl?: string;
  imageUrls?: string[];
}

const emptyForm = {
  facilityName: "",
  facilityNameEn: "",
  type: "",
  description: "",
  descriptionEn: "",
  imageUrl: "",
  imageUrls: ["", ""] as string[],
};

const normalizeImageSlots = (imageUrls: string[] | undefined, fallbackImage?: string, maxImages = 2) => {
  const normalized = Array.from(
    new Set([...(imageUrls || []), fallbackImage || ""].map((image) => image.trim()).filter(Boolean)),
  ).slice(0, maxImages);
  return [...normalized, ...Array(Math.max(0, maxImages - normalized.length)).fill("")];
};

export default function DashboardFacilitiesPage() {
  const { isAdmin, role } = useDashboardRole();
  const { localize } = useLanguage();
  const [facilities, setFacilities] = useState<FacilityItem[]>([]);
  const [form, setForm] = useState(emptyForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [isFormOpen, setIsFormOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState("ALL");
  const [imageFilter, setImageFilter] = useState<FacilityImageFilter>("ALL");
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [uploadingSlots, setUploadingSlots] = useState<Set<number>>(() => new Set());
  const [deleteTarget, setDeleteTarget] = useState<FacilityItem | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const isUploading = uploadingSlots.size > 0;

  const setSlotUploading = (index: number, uploading: boolean) => {
    setUploadingSlots((current) => {
      const next = new Set(current);
      if (uploading) next.add(index);
      else next.delete(index);
      return next;
    });
  };

  const showToast = useCallback((message: string, type: "success" | "error" | "info") => {
    setToast({ message, type });
  }, []);

  const fetchFacilities = useCallback(async (showLoading = false) => {
    if (showLoading) setIsLoading(true);
    try {
      const res = await cachedGet("/api/facilities");
      setFacilities(Array.isArray(res.data?.data) ? res.data.data : []);
    } catch {
      showToast("Không thể tải danh sách tiện nghi", "error");
    } finally {
      setIsLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void fetchFacilities(true);
    const refreshInBackground = () => void fetchFacilities(false);
    window.addEventListener("focus", refreshInBackground);
    return () => window.removeEventListener("focus", refreshInBackground);
  }, [fetchFacilities]);

  const facilityTypes = useMemo(
    () => Array.from(new Set(facilities.map((facility) => facility.type?.trim()).filter(Boolean) as string[])).sort((left, right) => left.localeCompare(right)),
    [facilities],
  );

  const filteredFacilities = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase();
    return facilities.filter((facility) => {
      const matchesSearch = !keyword || (
        facility.facilityName.toLowerCase().includes(keyword) ||
        (facility.facilityNameEn || "").toLowerCase().includes(keyword) ||
        (facility.type || "").toLowerCase().includes(keyword) ||
        (facility.description || "").toLowerCase().includes(keyword) ||
        (facility.descriptionEn || "").toLowerCase().includes(keyword)
      );
      const matchesType = typeFilter === "ALL" || facility.type === typeFilter;
      const hasImage = Boolean(facility.imageUrls?.some((image) => image?.trim()) || facility.imageUrl?.trim());
      const matchesImage = imageFilter === "ALL"
        || (imageFilter === "WITH_IMAGE" && hasImage)
        || (imageFilter === "WITHOUT_IMAGE" && !hasImage);
      return matchesSearch && matchesType && matchesImage;
    });
  }, [facilities, imageFilter, searchQuery, typeFilter]);

  const resetForm = () => {
    setForm(emptyForm);
    setEditingId(null);
    setFormErrors({});
    setUploadingSlots(new Set());
    setIsFormOpen(false);
  };

  const clearFormError = (key: string) => {
    setFormErrors((current) => {
      if (!current[key] && !current.form) return current;
      const next = { ...current };
      delete next[key];
      delete next.form;
      return next;
    });
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isUploading) {
      showToast(localize("Vui lòng đợi ảnh tải xong trước khi lưu.", "Please wait for the image upload to finish before saving."), "info");
      return;
    }
    const errors: Record<string, string> = {};
    const facilityName = form.facilityName.trim();
    const facilityNameEn = form.facilityNameEn.trim();
    if (facilityName.length < 2 || facilityName.length > 255) errors.facilityName = localize("Tên tiện nghi phải từ 2 đến 255 ký tự.", "Facility name must be 2-255 characters.");
    if (facilityNameEn.length > 255) errors.facilityNameEn = localize("Tên tiếng Anh không được quá 255 ký tự.", "English name must not exceed 255 characters.");
    if (!form.type.trim()) errors.type = localize("Vui lòng chọn phạm vi sử dụng.", "Select a usage scope.");
    if (form.description.trim().length > 1000) errors.description = localize("Mô tả tiếng Việt không được quá 1.000 ký tự.", "Vietnamese description must not exceed 1,000 characters.");
    if (form.descriptionEn.trim().length > 1000) errors.descriptionEn = localize("Mô tả tiếng Anh không được quá 1.000 ký tự.", "English description must not exceed 1,000 characters.");
    if (Object.keys(errors).length > 0) {
      setFormErrors(errors);
      const [firstKey, firstMessage] = Object.entries(errors)[0];
      showToast(firstMessage, "error");
      window.requestAnimationFrame(() => document.getElementById(`facility-${firstKey}`)?.focus());
      return;
    }
    setFormErrors({});

    setIsSaving(true);
    const imageUrls = Array.from(new Set(form.imageUrls.map((image) => image.trim()).filter(Boolean))).slice(0, 2);
    const payload = {
      facilityName: form.facilityName.trim(),
      facilityNameEn: form.facilityNameEn.trim(),
      type: form.type.trim(),
      description: form.description.trim(),
      descriptionEn: form.descriptionEn.trim(),
      imageUrl: imageUrls[0] || "",
      imageUrls,
    };

    try {
      if (editingId) {
        await apiClient.put(`/api/facilities/${editingId}`, payload);
        showToast("Cập nhật tiện nghi thành công", "success");
      } else {
        await apiClient.post("/api/facilities", payload);
        showToast("Thêm tiện nghi thành công", "success");
      }
      resetForm();
      await fetchFacilities();
    } catch (error: unknown) {
      const message = getApiErrorMessage(error, "Không thể lưu tiện nghi. Vui lòng kiểm tra dữ liệu");
      setFormErrors({ form: message });
      showToast(message, "error");
    } finally {
      setIsSaving(false);
    }
  };

  const handleEdit = (facility: FacilityItem) => {
    setEditingId(facility.id);
    setFormErrors({});
    setUploadingSlots(new Set());
    setForm({
      facilityName: facility.facilityName || "",
      facilityNameEn: facility.facilityNameEn || "",
      type: facility.type || "",
      description: facility.description || "",
      descriptionEn: facility.descriptionEn || "",
      imageUrl: facility.imageUrl || "",
      imageUrls: normalizeImageSlots(facility.imageUrls, facility.imageUrl),
    });
    setIsFormOpen(true);
  };

  const handleDelete = async () => {
    if (!deleteTarget || deletingId !== null) return;
    setDeletingId(deleteTarget.id);
    try {
      await apiClient.delete(`/api/facilities/${deleteTarget.id}`);
      showToast("Xóa tiện nghi thành công", "success");
      if (editingId === deleteTarget.id) resetForm();
      setDeleteTarget(null);
      await fetchFacilities();
    } catch {
      showToast("Không thể xóa tiện nghi này", "error");
    } finally {
      setDeletingId(null);
    }
  };

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-8 p-4 sm:p-6 lg:p-8">
      <div className="flex flex-col gap-4 border-b border-[#0F2A43]/5 pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.25em] text-[#80632F]">{localize("Thiết lập tiện nghi", "Facility settings")}</p>
          <h1 className="mt-2 font-serif text-3xl font-bold leading-tight tracking-tight text-[#0F2A43] md:text-4xl">{localize("Quản lý tiện nghi", "Facility management")}</h1>
          <p className="mt-1.5 text-sm font-semibold text-[#66727C]">{localize(`${facilities.length} tiện nghi đang hiển thị trên website`, `${facilities.length} facilities displayed on the website`)}</p>
        </div>
        <div className="flex flex-wrap gap-3">
          {isAdmin && (
            <button
              type="button"
              onClick={() => {
                resetForm();
                setIsFormOpen(true);
              }}
              className="self-start rounded-xl bg-[#0F2A43] px-5 py-2.5 text-sm font-bold text-white shadow-sm transition hover:bg-[#091E30]"
            >
              {localize("Thêm tiện nghi", "Add facility")}
            </button>
          )}
          <Link
            href="/facilities"
            className="self-start rounded-xl border border-[#0F2A43]/10 bg-white px-5 py-2.5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:text-[#80632F]"
          >
            {localize("Xem trang công khai", "View public page")}
          </Link>
          <button
            type="button"
            onClick={() => void fetchFacilities(false)}
            className="self-start rounded-xl border border-[#0F2A43]/10 bg-white px-5 py-2.5 text-sm font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:text-[#80632F]"
          >
            {localize("Làm mới dữ liệu", "Refresh data")}
          </button>
        </div>
      </div>

      {role === "STAFF" && (
        <div className="rounded-xl border border-[#B8944F]/30 bg-[#F0EADF] px-4 py-3 text-sm font-semibold text-[#80632F]">{localize("Bạn đang xem dữ liệu quản lý. Chỉ quản trị viên được thêm, sửa hoặc xóa tiện nghi.", "You have read-only access. Only administrators can add, edit or delete facilities.")}</div>
      )}

      {isAdmin && <ViewportModal
        open={isFormOpen}
        onClose={resetForm}
        labelledBy="facility-form-title"
        busy={isSaving || isUploading}
        panelClassName="max-w-5xl"
      >
      <form noValidate onSubmit={handleSubmit} className="lux-scrollbar min-h-0 w-full overflow-y-auto p-5 sm:p-7">
        <div className="mb-4 flex flex-wrap items-end justify-between gap-3 border-b border-[#0F2A43]/10 pb-4">
          <div><p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#80632F]">{editingId ? localize("Chỉnh sửa tiện nghi", "Edit facility") : localize("Tiện nghi mới", "New facility")}</p><h2 id="facility-form-title" className="mt-1 font-serif text-2xl font-bold text-[#0F2A43]">{localize("Thông tin hiển thị", "Display information")}</h2></div>
          <p className="text-xs text-[#66727C]">{localize("Điền nội dung ngắn gọn để thẻ tiện nghi dễ đọc.", "Keep content concise for readable facility cards.")}</p>
        </div>
        {formErrors.form && <p role="alert" className="mb-4 rounded-xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm font-semibold text-rose-700">{formErrors.form}</p>}
        <div className="grid min-w-0 gap-4 md:grid-cols-2 xl:grid-cols-12">
        <div className="min-w-0 xl:col-span-4">
          <label htmlFor="facility-facilityName" className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Tên tiện nghi (VI)", "Facility name (VI)")} *</label>
          <input
            id="facility-facilityName"
            data-modal-autofocus
            maxLength={255}
            value={form.facilityName}
            onChange={(event) => { setForm({ ...form, facilityName: event.target.value }); clearFormError("facilityName"); }}
            aria-invalid={Boolean(formErrors.facilityName)}
            className={`w-full min-w-0 rounded-xl border bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:ring-2 ${formErrors.facilityName ? "border-rose-500 focus:ring-rose-200" : "border-[#0F2A43]/10 focus:border-[#B8944F] focus:ring-[#B8944F]/20"}`}
            placeholder="Hồ bơi vô cực"
          />
          {formErrors.facilityName && <p className="mt-1.5 text-xs font-semibold text-rose-700">{formErrors.facilityName}</p>}
        </div>
        <div className="min-w-0 xl:col-span-4">
          <label htmlFor="facility-facilityNameEn" className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Tên tiện nghi (EN)", "Facility name (EN)")}</label>
          <input
            id="facility-facilityNameEn"
            maxLength={255}
            value={form.facilityNameEn}
            onChange={(event) => { setForm({ ...form, facilityNameEn: event.target.value }); clearFormError("facilityNameEn"); }}
            aria-invalid={Boolean(formErrors.facilityNameEn)}
            className={`w-full min-w-0 rounded-xl border bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:ring-2 ${formErrors.facilityNameEn ? "border-rose-500 focus:ring-rose-200" : "border-[#0F2A43]/10 focus:border-[#B8944F] focus:ring-[#B8944F]/20"}`}
            placeholder="Infinity pool"
          />
          {formErrors.facilityNameEn && <p className="mt-1.5 text-xs font-semibold text-rose-700">{formErrors.facilityNameEn}</p>}
        </div>
        <div className="min-w-0 xl:col-span-4">
          <label htmlFor="facility-type" className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Phân loại", "Category")} *</label>
          <select
            id="facility-type"
            value={form.type}
            onChange={(event) => { setForm({ ...form, type: event.target.value }); clearFormError("type"); }}
            aria-invalid={Boolean(formErrors.type)}
            className={`w-full min-w-0 rounded-xl border bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:ring-2 ${formErrors.type ? "border-rose-500 focus:ring-rose-200" : "border-[#0F2A43]/10 focus:border-[#B8944F] focus:ring-[#B8944F]/20"}`}
          >
            <option value="">{localize("Chọn phạm vi sử dụng", "Select usage scope")}</option>
            {Array.from(new Set(["ROOM", "PUBLIC", ...facilityTypes])).map((type) => (
              <option key={type} value={type}>{type === "ROOM" ? localize("Trong phòng", "In-room") : type === "PUBLIC" ? localize("Khu vực chung", "Public area") : type}</option>
            ))}
          </select>
          <p className="mt-1.5 text-[11px] text-[#66727C]">{localize("Hệ thống dùng phân loại này để tự nhóm tiện nghi khi hiển thị.", "The system uses this value to group facilities automatically.")}</p>
          {formErrors.type && <p className="mt-1 text-xs font-semibold text-rose-700">{formErrors.type}</p>}
        </div>
        <div className="grid min-w-0 gap-3 rounded-xl border border-[#0F2A43]/8 bg-white p-3 md:grid-cols-2 xl:col-span-8">
          {form.imageUrls.map((value, index) => (
            <ImageUploadField
              key={`facility-image-${index}`}
              id={`facility-image-upload-${index}`}
              folder="FACILITIES"
              value={value}
              label={index === 0
                ? localize("Ảnh đại diện", "Cover image")
                : localize("Ảnh chi tiết", "Detail image")}
              alt={localize(
                `${index === 0 ? "Ảnh đại diện" : "Ảnh chi tiết"} tiện nghi ${form.facilityName || "mới"}`,
                `${index === 0 ? "Cover" : "Detail"} image of ${form.facilityNameEn || form.facilityName || "new facility"}`,
              )}
              description={localize("Ảnh ngang JPEG, PNG hoặc WebP · tối đa 5 MB.", "Landscape JPEG, PNG or WebP · up to 5 MB.")}
              onUploadingChange={(uploading) => setSlotUploading(index, uploading)}
              onUploaded={(image) => setForm((current) => {
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
        <div className="min-w-0 xl:col-span-4">
          <label htmlFor="facility-description" className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Mô tả (VI)", "Description (VI)")}</label>
          <textarea
            id="facility-description"
            maxLength={1000}
            value={form.description}
            onChange={(event) => { setForm({ ...form, description: event.target.value }); clearFormError("description"); }}
            rows={4}
            aria-invalid={Boolean(formErrors.description)}
            className={`w-full min-w-0 resize-none rounded-xl border bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:ring-2 ${formErrors.description ? "border-rose-500 focus:ring-rose-200" : "border-[#0F2A43]/10 focus:border-[#B8944F] focus:ring-[#B8944F]/20"}`}
            placeholder="Mô tả ngắn hiển thị trên thẻ tiện nghi..."
          />
          <p className="mt-1 flex justify-between text-[11px] text-[#66727C]"><span>{formErrors.description || ""}</span><span>{form.description.length}/1000</span></p>
        </div>
        <div className="min-w-0 md:col-span-2 xl:col-span-4">
          <label htmlFor="facility-descriptionEn" className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Mô tả (EN)", "Description (EN)")}</label>
          <textarea
            id="facility-descriptionEn"
            maxLength={1000}
            value={form.descriptionEn}
            onChange={(event) => { setForm({ ...form, descriptionEn: event.target.value }); clearFormError("descriptionEn"); }}
            rows={4}
            aria-invalid={Boolean(formErrors.descriptionEn)}
            className={`w-full min-w-0 resize-none rounded-xl border bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:ring-2 ${formErrors.descriptionEn ? "border-rose-500 focus:ring-rose-200" : "border-[#0F2A43]/10 focus:border-[#B8944F] focus:ring-[#B8944F]/20"}`}
            placeholder="Short description for the public facility card..."
          />
          <p className="mt-1 flex justify-between text-[11px] text-[#66727C]"><span>{formErrors.descriptionEn || ""}</span><span>{form.descriptionEn.length}/1000</span></p>
        </div>
        </div>
        <div className="mt-5 flex flex-wrap gap-3 border-t border-[#0F2A43]/10 pt-4">
          <button
            type="submit"
            disabled={isSaving || isUploading}
            className="rounded-xl bg-[#0F2A43] px-5 py-2.5 text-sm font-bold text-white transition hover:bg-[#091E30] disabled:cursor-not-allowed disabled:opacity-60"
          >
            {isSaving
              ? localize("Đang lưu...", "Saving...")
              : isUploading
                ? localize("Đang tải ảnh...", "Uploading image...")
                : editingId
                  ? localize("Cập nhật tiện nghi", "Update facility")
                  : localize("Thêm tiện nghi", "Add facility")}
          </button>
          <button
            type="button"
            onClick={resetForm}
            disabled={isSaving || isUploading}
            className="rounded-xl border border-[#0F2A43]/10 bg-white px-5 py-2.5 text-sm font-bold text-[#66727C] transition hover:text-[#0F2A43] disabled:cursor-not-allowed disabled:opacity-55"
          >
            {localize("Hủy", "Cancel")}
          </button>
        </div>
      </form>
      </ViewportModal>}

      <DashboardFilterPanel
        title={localize("Bộ lọc danh mục tiện nghi", "Facility catalogue filters")}
        description={localize("Tra cứu theo tên, phân loại, mô tả và tình trạng hình ảnh", "Search by name, category, description and image availability")}
        resultCount={filteredFacilities.length}
        resultLabel={localize("tiện nghi phù hợp", "matching facilities")}
        resultNote={localize(`${facilityTypes.length} phân loại đang sử dụng`, `${facilityTypes.length} categories in use`)}
        hasActiveFilters={Boolean(searchQuery || typeFilter !== "ALL" || imageFilter !== "ALL")}
        activeFilterCount={Number(Boolean(searchQuery)) + Number(typeFilter !== "ALL") + Number(imageFilter !== "ALL")}
        activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
        onReset={() => {
          setSearchQuery("");
          setTypeFilter("ALL");
          setImageFilter("ALL");
        }}
        resetLabel={localize("Xóa toàn bộ bộ lọc", "Clear all filters")}
        actions={(
          <>
            <FilterQuickButton active={imageFilter === "WITH_IMAGE"} onClick={() => setImageFilter((current) => current === "WITH_IMAGE" ? "ALL" : "WITH_IMAGE")}>
              {localize("Đã có ảnh", "With image")}
            </FilterQuickButton>
            <FilterQuickButton active={imageFilter === "WITHOUT_IMAGE"} onClick={() => setImageFilter((current) => current === "WITHOUT_IMAGE" ? "ALL" : "WITHOUT_IMAGE")}>
              {localize("Thiếu ảnh", "Missing image")}
            </FilterQuickButton>
          </>
        )}
      >
        <div className="grid gap-4 md:grid-cols-[minmax(0,2fr)_minmax(12rem,1fr)_minmax(12rem,1fr)]">
          <DashboardSearchField
            id="facility-search"
            label={localize("Tìm kiếm", "Search")}
            value={searchQuery}
            onChange={setSearchQuery}
            placeholder={localize("Tên tiện nghi, phân loại hoặc nội dung mô tả...", "Facility name, category or description...")}
            clearLabel={localize("Xóa từ khóa", "Clear search")}
          />
          <DashboardSelectField id="facility-type" label={localize("Phân loại", "Category")} value={typeFilter} onChange={(event) => setTypeFilter(event.target.value)}>
            <option value="ALL">{localize("Tất cả phân loại", "All categories")}</option>
            {facilityTypes.map((type) => <option key={type} value={type}>{type}</option>)}
          </DashboardSelectField>
          <DashboardSelectField id="facility-image" label={localize("Hình ảnh", "Image status")} value={imageFilter} onChange={(event) => setImageFilter(event.target.value as FacilityImageFilter)}>
            <option value="ALL">{localize("Tất cả", "All")}</option>
            <option value="WITH_IMAGE">{localize("Đã có ảnh", "With image")}</option>
            <option value="WITHOUT_IMAGE">{localize("Chưa có ảnh", "Without image")}</option>
          </DashboardSelectField>
        </div>
      </DashboardFilterPanel>

      {isLoading ? (
        <div className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3" role="status" aria-label={localize("Đang tải tiện nghi", "Loading facilities")}>
          {[0, 1, 2, 3, 4, 5].map((item) => <div key={item} className="overflow-hidden rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#FBFAF6]"><div className="aspect-[16/10] animate-pulse bg-[#E5E9ED]" /><div className="space-y-3 p-5"><div className="h-5 w-24 animate-pulse rounded bg-[#E5E9ED]" /><div className="h-7 w-2/3 animate-pulse rounded bg-[#E5E9ED]" /><div className="h-12 animate-pulse rounded bg-[#E5E9ED]" /></div></div>)}
        </div>
      ) : filteredFacilities.length === 0 ? (
        <div className="rounded-[1.5rem] border-2 border-dashed border-[#0F2A43]/10 bg-[#FBFAF6] p-10 text-center text-sm font-bold text-[#66727C]">{localize("Không tìm thấy tiện nghi phù hợp.", "No matching facilities found.")}</div>
      ) : (
        <div className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
          {filteredFacilities.map((facility) => (
            <article key={facility.id} className="overflow-hidden rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#FBFAF6] shadow-sm transition hover:border-[#B8944F]/50 even:bg-[#EAE2D2]">
              <div className="relative aspect-[16/10] overflow-hidden bg-[#EAE2D2]">
                {(facility.imageUrls?.[0] || facility.imageUrl) ? (
                  <Image
                    src={resolveMediaSource(facility.imageUrls?.[0] || facility.imageUrl || "")}
                    alt={localize(facility.facilityName, facility.facilityNameEn)}
                    fill
                    sizes="(min-width: 1280px) 33vw, (min-width: 768px) 50vw, 100vw"
                    className="object-cover"
                  />
                ) : (
                  <div className="flex h-full w-full items-center justify-center text-sm font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</div>
                )}
              </div>
              <div className="space-y-4 p-5">
                <div>
                  <span className="inline-flex rounded-lg bg-[#F0EADF] px-2.5 py-1 text-[10px] font-bold uppercase tracking-wider text-[#80632F]">
                    {facility.type === "ROOM" ? localize("Trong phòng", "In-room") : facility.type === "PUBLIC" ? localize("Khu vực chung", "Public area") : facility.type || localize("Chung", "General")}
                  </span>
                  <h3 className="mt-3 font-serif text-xl font-bold text-[#0F2A43]">{localize(facility.facilityName, facility.facilityNameEn)}</h3>
                  <p className="mt-2 min-h-12 text-sm font-medium leading-relaxed text-[#66727C]">{localize(facility.description, facility.descriptionEn) || localize("Chưa có mô tả cho tiện nghi này.", "No description is available for this facility.")}</p>
                </div>
                {isAdmin && <div className="flex gap-3 border-t border-[#0F2A43]/5 pt-4">
                  <button
                    type="button"
                    onClick={() => handleEdit(facility)}
                    className="rounded-lg border border-[#0F2A43]/10 px-3.5 py-2 text-xs font-bold text-[#66727C] transition hover:border-[#B8944F] hover:text-[#80632F]"
                  >
                    {localize("Sửa", "Edit")}
                  </button>
                  <button
                    type="button"
                    onClick={() => setDeleteTarget(facility)}
                    className="rounded-lg border border-rose-200 px-3.5 py-2 text-xs font-bold text-rose-600 transition hover:bg-rose-50"
                  >
                    {localize("Xóa", "Delete")}
                  </button>
                </div>}
              </div>
            </article>
          ))}
        </div>
      )}

      {deleteTarget && (
        <ViewportModal
          open
          onClose={() => setDeleteTarget(null)}
          labelledBy="delete-facility-title"
          describedBy="delete-facility-description"
          busy={deletingId === deleteTarget.id}
          panelClassName="max-w-md"
        >
          <div className="p-6">
            <p className="text-xs font-bold uppercase tracking-[0.18em] text-rose-700">{localize("Thao tác không thể hoàn tác", "Irreversible action")}</p>
            <h2 id="delete-facility-title" className="mt-2 font-serif text-2xl font-bold text-[#0F2A43]">{localize("Xóa tiện nghi?", "Delete facility?")}</h2>
            <p id="delete-facility-description" className="mt-3 text-sm leading-6 text-[#66727C]">{localize(`Bạn sắp xóa “${deleteTarget.facilityName}”. Hãy kiểm tra các hạng phòng đang sử dụng tiện nghi này trước khi tiếp tục.`, `You are about to delete “${deleteTarget.facilityNameEn || deleteTarget.facilityName}”. Check room types using this facility before continuing.`)}</p>
          </div>
          <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 px-6 py-4 sm:flex-row sm:justify-end">
            <button type="button" disabled={deletingId === deleteTarget.id} onClick={() => setDeleteTarget(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43] transition hover:bg-[#F1F0EA] disabled:opacity-50">{localize("Giữ lại", "Keep facility")}</button>
            <button type="button" disabled={deletingId === deleteTarget.id} onClick={() => void handleDelete()} className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white transition hover:bg-rose-800 disabled:cursor-wait disabled:opacity-50">{deletingId === deleteTarget.id && <span aria-hidden="true" className="h-4 w-4 animate-spin rounded-full border-2 border-white border-r-transparent" />}{deletingId === deleteTarget.id ? localize("Đang xóa...", "Deleting...") : localize("Xác nhận xóa", "Delete facility")}</button>
          </footer>
        </ViewportModal>
      )}

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
