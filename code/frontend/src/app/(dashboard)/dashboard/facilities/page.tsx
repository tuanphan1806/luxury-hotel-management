"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { apiClient, cachedGet } from "@/lib/api";
import { resolveMediaSource } from "@/lib/media-url";
import Toast from "@/components/UI/Toast";
import ImageUploadField from "@/components/UI/ImageUploadField";
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
}

const emptyForm = {
  facilityName: "",
  facilityNameEn: "",
  type: "",
  description: "",
  descriptionEn: "",
  imageUrl: "",
};

export default function DashboardFacilitiesPage() {
  const { isAdmin, role } = useDashboardRole();
  const { localize } = useLanguage();
  const [facilities, setFacilities] = useState<FacilityItem[]>([]);
  const [form, setForm] = useState(emptyForm);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [typeFilter, setTypeFilter] = useState("ALL");
  const [imageFilter, setImageFilter] = useState<FacilityImageFilter>("ALL");
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const showToast = useCallback((message: string, type: "success" | "error" | "info") => {
    setToast({ message, type });
  }, []);

  const fetchFacilities = useCallback(async (showLoading = false) => {
    if (showLoading) setIsLoading(true);
    try {
      const res = await cachedGet("/api/facilities");
      setFacilities(Array.isArray(res.data?.data) ? res.data.data : []);
    } catch {
      showToast("Không thể tải danh sách tiện ích", "error");
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
      const hasImage = Boolean(facility.imageUrl?.trim());
      const matchesImage = imageFilter === "ALL"
        || (imageFilter === "WITH_IMAGE" && hasImage)
        || (imageFilter === "WITHOUT_IMAGE" && !hasImage);
      return matchesSearch && matchesType && matchesImage;
    });
  }, [facilities, imageFilter, searchQuery, typeFilter]);

  const resetForm = () => {
    setForm(emptyForm);
    setEditingId(null);
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isUploading) {
      showToast(localize("Vui lòng đợi ảnh tải xong trước khi lưu.", "Please wait for the image upload to finish before saving."), "info");
      return;
    }
    if (!form.facilityName.trim()) {
      showToast("Vui lòng nhập tên tiện ích", "error");
      return;
    }

    setIsSaving(true);
    const payload = {
      facilityName: form.facilityName.trim(),
      facilityNameEn: form.facilityNameEn.trim(),
      type: form.type.trim(),
      description: form.description.trim(),
      descriptionEn: form.descriptionEn.trim(),
      imageUrl: form.imageUrl.trim(),
    };

    try {
      if (editingId) {
        await apiClient.put(`/api/facilities/${editingId}`, payload);
        showToast("Cập nhật tiện ích thành công", "success");
      } else {
        await apiClient.post("/api/facilities", payload);
        showToast("Thêm tiện ích thành công", "success");
      }
      resetForm();
      await fetchFacilities();
    } catch {
      showToast("Không thể lưu tiện ích. Vui lòng kiểm tra dữ liệu", "error");
    } finally {
      setIsSaving(false);
    }
  };

  const handleEdit = (facility: FacilityItem) => {
    setEditingId(facility.id);
    setForm({
      facilityName: facility.facilityName || "",
      facilityNameEn: facility.facilityNameEn || "",
      type: facility.type || "",
      description: facility.description || "",
      descriptionEn: facility.descriptionEn || "",
      imageUrl: facility.imageUrl || "",
    });
    window.scrollTo({ top: 0, behavior: "smooth" });
  };

  const handleDelete = async (facility: FacilityItem) => {
    if (!window.confirm(localize(`Xóa tiện ích "${facility.facilityName}"?`, `Delete facility "${facility.facilityNameEn || facility.facilityName}"?`))) return;
    try {
      await apiClient.delete(`/api/facilities/${facility.id}`);
      showToast("Xóa tiện ích thành công", "success");
      if (editingId === facility.id) resetForm();
      await fetchFacilities();
    } catch {
      showToast("Không thể xóa tiện ích này", "error");
    }
  };

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-8 p-4 sm:p-6 lg:p-8">
      <div className="flex flex-col gap-4 border-b border-[#0F2A43]/5 pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.25em] text-[#80632F]">{localize("Thiết lập tiện ích", "Facility settings")}</p>
          <h1 className="mt-2 font-serif text-3xl font-bold leading-tight tracking-tight text-[#0F2A43] md:text-4xl">{localize("Quản lý tiện ích", "Facility management")}</h1>
          <p className="mt-1.5 text-sm font-semibold text-[#66727C]">{localize(`${facilities.length} tiện ích đang hiển thị trên website`, `${facilities.length} facilities displayed on the website`)}</p>
        </div>
        <div className="flex flex-wrap gap-3">
          <Link
            href="/facilities"
            className="self-start rounded-xl bg-[#0F2A43] px-5 py-2.5 text-sm font-bold text-white transition hover:bg-[#091E30]"
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
        <div className="rounded-xl border border-[#B8944F]/30 bg-[#F0EADF] px-4 py-3 text-sm font-semibold text-[#80632F]">{localize("Bạn đang xem dữ liệu quản lý. Chỉ quản trị viên được thêm, sửa hoặc xóa tiện ích.", "You have read-only access. Only administrators can add, edit or delete facilities.")}</div>
      )}

      {isAdmin && <form onSubmit={handleSubmit} className="grid gap-4 rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#FBFAF6] p-5 shadow-sm lg:grid-cols-[1.1fr_0.8fr_1.4fr]">
        <div>
          <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Tên tiện ích (VI)", "Facility name (VI)")}</label>
          <input
            value={form.facilityName}
            onChange={(event) => setForm({ ...form, facilityName: event.target.value })}
            className="w-full rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:border-[#B8944F]"
            placeholder="Hồ bơi vô cực"
          />
        </div>
        <div>
          <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Tên tiện ích (EN)", "Facility name (EN)")}</label>
          <input
            value={form.facilityNameEn}
            onChange={(event) => setForm({ ...form, facilityNameEn: event.target.value })}
            className="w-full rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:border-[#B8944F]"
            placeholder="Infinity pool"
          />
        </div>
        <div>
          <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Phân loại", "Category")}</label>
          <select
            value={form.type}
            onChange={(event) => setForm({ ...form, type: event.target.value })}
            className="w-full rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:border-[#B8944F]"
          >
            <option value="">{localize("Chọn phạm vi sử dụng", "Select usage scope")}</option>
            {Array.from(new Set(["ROOM", "PUBLIC", ...facilityTypes])).map((type) => (
              <option key={type} value={type}>{type === "ROOM" ? localize("Trong phòng", "In-room") : type === "PUBLIC" ? localize("Khu vực chung", "Public area") : type}</option>
            ))}
          </select>
          <p className="mt-1.5 text-[11px] text-[#66727C]">{localize("Hệ thống dùng phân loại này để tự nhóm tiện ích khi hiển thị.", "The system uses this value to group facilities automatically.")}</p>
        </div>
        <ImageUploadField
          id="facility-image-upload"
          folder="FACILITIES"
          value={form.imageUrl}
          label={localize("Ảnh tiện ích", "Facility image")}
          alt={localize(`Ảnh xem trước tiện ích ${form.facilityName || "mới"}`, `Preview of ${form.facilityNameEn || form.facilityName || "new facility"}`)}
          description={localize("Ảnh ngang JPEG, PNG hoặc WebP · tối đa 5 MB.", "Landscape JPEG, PNG or WebP · up to 5 MB.")}
          onUploadingChange={setIsUploading}
          onUploaded={(image) => setForm((current) => ({ ...current, imageUrl: image.url }))}
        />
        <div>
          <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Mô tả (VI)", "Description (VI)")}</label>
          <textarea
            value={form.description}
            onChange={(event) => setForm({ ...form, description: event.target.value })}
            rows={3}
            className="w-full resize-none rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:border-[#B8944F]"
            placeholder="Mô tả ngắn hiển thị trên thẻ tiện ích..."
          />
        </div>
        <div className="lg:col-span-2">
          <label className="mb-2 block text-xs font-bold uppercase tracking-wider text-[#66727C]">{localize("Mô tả (EN)", "Description (EN)")}</label>
          <textarea
            value={form.descriptionEn}
            onChange={(event) => setForm({ ...form, descriptionEn: event.target.value })}
            rows={3}
            className="w-full resize-none rounded-xl border border-[#0F2A43]/10 bg-[#F1F0EA] px-4 py-3 text-sm font-semibold outline-none transition focus:border-[#B8944F]"
            placeholder="Short description for the public facility card..."
          />
        </div>
        <div className="flex flex-wrap gap-3 lg:col-span-3">
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
                  ? localize("Cập nhật tiện ích", "Update facility")
                  : localize("Thêm tiện ích", "Add facility")}
          </button>
          {editingId && (
            <button
              type="button"
              onClick={resetForm}
              disabled={isUploading}
              className="rounded-xl border border-[#0F2A43]/10 bg-white px-5 py-2.5 text-sm font-bold text-[#66727C] transition hover:text-[#0F2A43] disabled:cursor-not-allowed disabled:opacity-55"
            >
              {localize("Hủy chỉnh sửa", "Cancel edit")}
            </button>
          )}
        </div>
      </form>}

      <DashboardFilterPanel
        title={localize("Bộ lọc danh mục tiện ích", "Facility catalogue filters")}
        description={localize("Tra cứu theo tên, phân loại, mô tả và tình trạng hình ảnh", "Search by name, category, description and image availability")}
        resultCount={filteredFacilities.length}
        resultLabel={localize("tiện ích phù hợp", "matching facilities")}
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
            placeholder={localize("Tên tiện ích, phân loại hoặc nội dung mô tả...", "Facility name, category or description...")}
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
        <div className="rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#FBFAF6] p-10 text-center text-sm font-bold text-[#66727C]">{localize("Đang tải tiện ích...", "Loading facilities...")}</div>
      ) : filteredFacilities.length === 0 ? (
        <div className="rounded-[1.5rem] border-2 border-dashed border-[#0F2A43]/10 bg-[#FBFAF6] p-10 text-center text-sm font-bold text-[#66727C]">{localize("Không tìm thấy tiện ích phù hợp.", "No matching facilities found.")}</div>
      ) : (
        <div className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
          {filteredFacilities.map((facility) => (
            <article key={facility.id} className="overflow-hidden rounded-[1.5rem] border border-[#0F2A43]/10 bg-[#FBFAF6] shadow-sm transition hover:border-[#B8944F]/50 even:bg-[#EAE2D2]">
              <div className="relative aspect-[16/10] overflow-hidden bg-[#EAE2D2]">
                {facility.imageUrl ? (
                  <Image
                    src={resolveMediaSource(facility.imageUrl)}
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
                  <p className="mt-2 min-h-12 text-sm font-medium leading-relaxed text-[#66727C]">{localize(facility.description, facility.descriptionEn) || localize("Chưa có mô tả cho tiện ích này.", "No description is available for this facility.")}</p>
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
                    onClick={() => handleDelete(facility)}
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

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
