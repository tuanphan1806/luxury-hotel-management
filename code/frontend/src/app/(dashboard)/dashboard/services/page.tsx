"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import ImageUploadField from "@/components/UI/ImageUploadField";
import Toast from "@/components/UI/Toast";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { apiClient, getApiErrorMessage } from "@/lib/api";
import type {
  AddOnPricingUnit,
  AddOnServiceCategory,
  AddOnServiceItem,
} from "@/lib/add-on-services";
import { resolveMediaSource } from "@/lib/media-url";

type ActiveFilter = "ALL" | "ACTIVE" | "INACTIVE";
type FlowFilter = "ALL" | "BOOKING_TIME" | "IN_STAY";

interface ServiceForm {
  code: string;
  name: string;
  nameEn: string;
  description: string;
  descriptionEn: string;
  imageUrl: string;
  category: AddOnServiceCategory;
  price: string;
  pricingUnit: AddOnPricingUnit;
  bookingEnabled: boolean;
  inStayEnabled: boolean;
  sortOrder: string;
}

const emptyForm: ServiceForm = {
  code: "",
  name: "",
  nameEn: "",
  description: "",
  descriptionEn: "",
  imageUrl: "",
  category: "OTHER",
  price: "",
  pricingUnit: "PER_USE",
  bookingEnabled: true,
  inStayEnabled: true,
  sortOrder: "0",
};

const categories: AddOnServiceCategory[] = [
  "FOOD_BEVERAGE",
  "AMENITY",
  "EQUIPMENT",
  "DECORATION",
  "OTHER",
];

const pricingUnits: AddOnPricingUnit[] = [
  "PER_GUEST",
  "PER_NIGHT",
  "PER_ITEM",
  "PER_ORDER",
  "PER_USE",
];

export default function DashboardServicesPage() {
  const { isAdmin } = useDashboardRole();
  const { localeTag, localize } = useLanguage();
  const [services, setServices] = useState<AddOnServiceItem[]>([]);
  const [search, setSearch] = useState("");
  const [activeFilter, setActiveFilter] = useState<ActiveFilter>("ALL");
  const [flowFilter, setFlowFilter] = useState<FlowFilter>("ALL");
  const [categoryFilter, setCategoryFilter] = useState("ALL");
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isUploading, setIsUploading] = useState(false);
  const [isFormOpen, setIsFormOpen] = useState(false);
  const [editing, setEditing] = useState<AddOnServiceItem | null>(null);
  const [form, setForm] = useState<ServiceForm>(emptyForm);
  const [formErrors, setFormErrors] = useState<Record<string, string>>({});
  const [activationTarget, setActivationTarget] = useState<AddOnServiceItem | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const categoryLabel = (value: AddOnServiceCategory) => ({
    FOOD_BEVERAGE: localize("Ẩm thực", "Food & beverage"),
    AMENITY: localize("Tiện ích lưu trú", "Stay amenity"),
    EQUIPMENT: localize("Thiết bị", "Equipment"),
    DECORATION: localize("Trang trí", "Decoration"),
    OTHER: localize("Khác", "Other"),
  }[value]);

  const unitLabel = (value: AddOnPricingUnit) => ({
    PER_GUEST: localize("Theo người", "Per guest"),
    PER_NIGHT: localize("Theo món / đêm", "Per item / night"),
    PER_ITEM: localize("Theo món", "Per item"),
    PER_ORDER: localize("Theo đơn", "Per order"),
    PER_USE: localize("Theo lần", "Per use"),
  }[value]);

  const money = (value: number) => Number(value || 0).toLocaleString(localeTag, {
    style: "currency",
    currency: "VND",
    maximumFractionDigits: 0,
  });

  const loadServices = useCallback(async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get("/api/add-on-services/admin");
      const payload = response.data?.data ?? response.data;
      setServices(Array.isArray(payload)
        ? payload.map((item) => ({ ...item, id: Number(item.id), price: Number(item.price || 0) }))
        : []);
    } catch (error: unknown) {
      setToast({
        message: getApiErrorMessage(error, localize("Không thể tải danh mục dịch vụ.", "Could not load the service catalog.")),
        type: "error",
      });
    } finally {
      setIsLoading(false);
    }
  }, [localize]);

  useEffect(() => {
    if (isAdmin) void loadServices();
  }, [isAdmin, loadServices]);

  const filtered = useMemo(() => {
    const keyword = search.trim().toLocaleLowerCase();
    return services.filter((service) => {
      const matchesSearch = !keyword || [
        service.code,
        service.name,
        service.nameEn,
        service.description,
        service.descriptionEn,
      ].some((value) => (value || "").toLocaleLowerCase().includes(keyword));
      const matchesActive = activeFilter === "ALL"
        || (activeFilter === "ACTIVE" && service.active)
        || (activeFilter === "INACTIVE" && !service.active);
      const matchesFlow = flowFilter === "ALL"
        || (flowFilter === "BOOKING_TIME" && service.bookingEnabled)
        || (flowFilter === "IN_STAY" && service.inStayEnabled);
      const matchesCategory = categoryFilter === "ALL" || service.category === categoryFilter;
      return matchesSearch && matchesActive && matchesFlow && matchesCategory;
    });
  }, [activeFilter, categoryFilter, flowFilter, search, services]);

  const openCreate = () => {
    setEditing(null);
    setForm({ ...emptyForm });
    setFormErrors({});
    setIsFormOpen(true);
  };

  const openEdit = (service: AddOnServiceItem) => {
    setEditing(service);
    setForm({
      code: service.code,
      name: service.name,
      nameEn: service.nameEn || "",
      description: service.description || "",
      descriptionEn: service.descriptionEn || "",
      imageUrl: service.imageUrl || "",
      category: service.category,
      price: String(service.price),
      pricingUnit: service.pricingUnit,
      bookingEnabled: service.bookingEnabled,
      inStayEnabled: service.inStayEnabled,
      sortOrder: String(service.sortOrder || 0),
    });
    setFormErrors({});
    setIsFormOpen(true);
  };

  const closeForm = () => {
    if (isSaving || isUploading) return;
    setIsFormOpen(false);
    setEditing(null);
    setForm({ ...emptyForm });
    setFormErrors({});
  };

  const validate = () => {
    const errors: Record<string, string> = {};
    const price = Number(form.price);
    const sortOrder = Number(form.sortOrder);
    if (!/^[A-Za-z0-9_-]{2,64}$/.test(form.code.trim())) {
      errors.code = localize("Mã gồm 2–64 chữ, số, _ hoặc -.", "Use 2–64 letters, numbers, _ or -.");
    }
    if (form.name.trim().length < 2 || form.name.trim().length > 255) {
      errors.name = localize("Tên tiếng Việt phải từ 2 đến 255 ký tự.", "Vietnamese name must be 2–255 characters.");
    }
    if (form.nameEn.trim().length > 255) errors.nameEn = localize("Tên tiếng Anh tối đa 255 ký tự.", "English name is limited to 255 characters.");
    if (form.description.trim().length > 2000) errors.description = localize("Mô tả tối đa 2.000 ký tự.", "Description is limited to 2,000 characters.");
    if (!Number.isSafeInteger(price) || price < 0 || !/^\d{1,10}$/.test(form.price.trim())) {
      errors.price = localize("Giá phải là số VND nguyên không âm.", "Price must be a non-negative whole VND amount.");
    }
    if (!Number.isInteger(sortOrder) || sortOrder < 0 || sortOrder > 100000) {
      errors.sortOrder = localize("Thứ tự phải là số nguyên từ 0 đến 100.000.", "Sort order must be an integer from 0 to 100,000.");
    }
    setFormErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!validate()) return;
    setIsSaving(true);
    try {
      const payload = {
        code: form.code.trim().toUpperCase(),
        name: form.name.trim(),
        nameEn: form.nameEn.trim() || undefined,
        description: form.description.trim() || undefined,
        descriptionEn: form.descriptionEn.trim() || undefined,
        imageUrl: form.imageUrl.trim() || undefined,
        category: form.category,
        price: Number(form.price),
        pricingUnit: form.pricingUnit,
        bookingEnabled: form.bookingEnabled,
        inStayEnabled: form.inStayEnabled,
        sortOrder: Number(form.sortOrder),
      };
      if (editing) await apiClient.put(`/api/add-on-services/admin/${editing.id}`, payload);
      else await apiClient.post("/api/add-on-services/admin", payload);
      setToast({
        message: editing
          ? localize("Đã cập nhật dịch vụ.", "Service updated.")
          : localize("Đã tạo dịch vụ.", "Service created."),
        type: "success",
      });
      setIsFormOpen(false);
      setEditing(null);
      setForm({ ...emptyForm });
      setFormErrors({});
      await loadServices();
    } catch (error: unknown) {
      setToast({
        message: getApiErrorMessage(error, localize("Không thể lưu dịch vụ.", "Could not save the service.")),
        type: "error",
      });
    } finally {
      setIsSaving(false);
    }
  };

  const changeActivation = async () => {
    if (!activationTarget) return;
    setIsSaving(true);
    try {
      await apiClient.patch(
        `/api/add-on-services/admin/${activationTarget.id}/activation`,
        null,
        { params: { active: !activationTarget.active } },
      );
      setToast({
        message: activationTarget.active
          ? localize("Đã ngừng cung cấp dịch vụ.", "Service deactivated.")
          : localize("Đã kích hoạt lại dịch vụ.", "Service reactivated."),
        type: "success",
      });
      setActivationTarget(null);
      await loadServices();
    } catch (error: unknown) {
      setToast({
        message: getApiErrorMessage(error, localize("Không thể đổi trạng thái dịch vụ.", "Could not change service status.")),
        type: "error",
      });
    } finally {
      setIsSaving(false);
    }
  };

  if (!isAdmin) {
    return (
      <div className="ops-page mx-auto max-w-3xl p-6">
        <div className="rounded-2xl border border-rose-200 bg-rose-50 p-6 text-rose-800">
          <h1 className="text-xl font-bold">{localize("Chỉ ADMIN được quản lý dịch vụ thêm", "Only ADMIN can manage add-on services")}</h1>
        </div>
      </div>
    );
  }

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6 lg:p-8">
      <header className="flex flex-col gap-4 border-b border-[#0F2A43]/8 pb-5 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.22em] text-[#80632F]">{localize("Quản lý doanh thu phụ trợ", "Ancillary revenue management")}</p>
          <h1 className="mt-2 text-3xl font-bold text-[#0F2A43]">{localize("Dịch vụ thêm", "Add-on services")}</h1>
          <p className="mt-2 text-sm text-[#66727C]">{localize("Dữ liệu từ database dùng chung cho đặt trước và phục vụ trong kỳ lưu trú.", "Database-backed catalog shared by booking-time and in-stay flows.")}</p>
        </div>
        <button type="button" onClick={openCreate} className="min-h-11 rounded-xl bg-[#0F2A43] px-5 text-sm font-bold text-white transition hover:bg-[#091E30]">
          + {localize("Thêm dịch vụ", "Add service")}
        </button>
      </header>

      <DashboardFilterPanel
        title={localize("Lọc danh mục", "Catalog filters")}
        resultCount={filtered.length}
        resultLabel={localize("dịch vụ", "services")}
        resultNote={localize(`trên tổng số ${services.length}`, `of ${services.length} total`)}
        hasActiveFilters={Boolean(search || activeFilter !== "ALL" || flowFilter !== "ALL" || categoryFilter !== "ALL")}
        activeFilterCount={[search, activeFilter !== "ALL", flowFilter !== "ALL", categoryFilter !== "ALL"].filter(Boolean).length}
        activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
        onReset={() => { setSearch(""); setActiveFilter("ALL"); setFlowFilter("ALL"); setCategoryFilter("ALL"); }}
        resetLabel={localize("Xóa bộ lọc", "Clear filters")}
        actions={(
          <>
            <FilterQuickButton active={activeFilter === "ACTIVE"} onClick={() => setActiveFilter(activeFilter === "ACTIVE" ? "ALL" : "ACTIVE")}>{localize("Đang hoạt động", "Active")}</FilterQuickButton>
            <FilterQuickButton active={flowFilter === "BOOKING_TIME"} onClick={() => setFlowFilter(flowFilter === "BOOKING_TIME" ? "ALL" : "BOOKING_TIME")}>{localize("Đặt trước", "Booking-time")}</FilterQuickButton>
            <FilterQuickButton active={flowFilter === "IN_STAY"} onClick={() => setFlowFilter(flowFilter === "IN_STAY" ? "ALL" : "IN_STAY")}>{localize("Trong kỳ lưu trú", "In-stay")}</FilterQuickButton>
          </>
        )}
      >
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <DashboardSearchField id="add-on-service-search" label={localize("Tìm kiếm", "Search")} value={search} onChange={setSearch} placeholder={localize("Tên hoặc mã dịch vụ", "Service name or code")} />
        <DashboardSelectField id="add-on-service-status" label={localize("Trạng thái", "Status")} value={activeFilter} onChange={(event) => setActiveFilter(event.target.value as ActiveFilter)}>
          <option value="ALL">{localize("Tất cả", "All")}</option>
          <option value="ACTIVE">{localize("Đang hoạt động", "Active")}</option>
          <option value="INACTIVE">{localize("Đã ngừng", "Inactive")}</option>
        </DashboardSelectField>
        <DashboardSelectField id="add-on-service-flow" label={localize("Luồng", "Flow")} value={flowFilter} onChange={(event) => setFlowFilter(event.target.value as FlowFilter)}>
          <option value="ALL">{localize("Mọi luồng", "All flows")}</option>
          <option value="BOOKING_TIME">{localize("Đặt trước", "Booking-time")}</option>
          <option value="IN_STAY">{localize("Trong kỳ lưu trú", "In-stay")}</option>
        </DashboardSelectField>
        <DashboardSelectField id="add-on-service-category" label={localize("Phân loại", "Category")} value={categoryFilter} onChange={(event) => setCategoryFilter(event.target.value)}>
          <option value="ALL">{localize("Tất cả", "All")}</option>
          {categories.map((category) => <option key={category} value={category}>{categoryLabel(category)}</option>)}
        </DashboardSelectField>
        </div>
      </DashboardFilterPanel>

      {isLoading ? (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3" role="status">
          {[1, 2, 3, 4, 5, 6].map((item) => <div key={item} className="h-56 animate-pulse rounded-2xl bg-[#E5E9ED]" />)}
        </div>
      ) : filtered.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-[#0F2A43]/20 bg-white p-12 text-center">
          <h2 className="font-serif text-xl font-bold text-[#0F2A43]">{localize("Không có dịch vụ phù hợp", "No matching services")}</h2>
          <p className="mt-2 text-sm text-[#66727C]">{localize("Thử xóa bộ lọc hoặc tạo dịch vụ mới.", "Clear filters or create a new service.")}</p>
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((service) => (
            <article key={service.id} className={`overflow-hidden rounded-2xl border bg-white shadow-sm transition hover:-translate-y-0.5 hover:shadow-lg ${service.active ? "border-[#0F2A43]/12" : "border-[#0F2A43]/8 opacity-70"}`}>
              <div className="grid grid-cols-[8rem_1fr]">
                <div className="relative min-h-40 bg-[#E5E9ED]">
                  {service.imageUrl ? <ProgressiveImage src={resolveMediaSource(service.imageUrl)} alt={localize(service.name, service.nameEn)} fill sizes="128px" className="object-cover" /> : <span className="flex h-full items-center justify-center text-xs font-semibold text-[#66727C]">{localize("Chưa có ảnh", "No image")}</span>}
                </div>
                <div className="min-w-0 p-4">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0"><p className="truncate text-[10px] font-bold uppercase tracking-wider text-[#80632F]">{service.code}</p><h2 className="mt-1 font-serif text-lg font-bold text-[#0F2A43]">{localize(service.name, service.nameEn)}</h2></div>
                    <span className={`shrink-0 rounded-full px-2 py-1 text-[9px] font-black ${service.active ? "bg-emerald-50 text-emerald-700" : "bg-[#E5E9ED] text-[#66727C]"}`}>{service.active ? localize("Đang bán", "Active") : localize("Đã ngừng", "Inactive")}</span>
                  </div>
                  <p className="mt-2 line-clamp-2 text-xs leading-5 text-[#66727C]">{localize(service.description, service.descriptionEn) || "—"}</p>
                  <p className="mt-3 text-lg font-black tabular-nums text-[#80632F]">{money(service.price)} <span className="text-[10px] font-semibold text-[#66727C]">· {unitLabel(service.pricingUnit)}</span></p>
                </div>
              </div>
              <div className="flex flex-wrap items-center gap-2 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-4 py-3">
                <span className="rounded-full bg-[#EAE2D2] px-2.5 py-1 text-[10px] font-bold text-[#0F2A43]">{categoryLabel(service.category)}</span>
                {service.bookingEnabled && <span className="rounded-full bg-sky-50 px-2.5 py-1 text-[10px] font-bold text-sky-700">{localize("Đặt trước", "Booking-time")}</span>}
                {service.inStayEnabled && <span className="rounded-full bg-violet-50 px-2.5 py-1 text-[10px] font-bold text-violet-700">{localize("Trong kỳ lưu trú", "In-stay")}</span>}
                <div className="ml-auto flex gap-2">
                  <button type="button" onClick={() => openEdit(service)} className="min-h-10 rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-xs font-bold text-[#0F2A43] hover:border-[#B8944F]">{localize("Sửa", "Edit")}</button>
                  <button type="button" onClick={() => setActivationTarget(service)} className={`min-h-10 rounded-lg px-3 text-xs font-bold ${service.active ? "border border-rose-200 bg-rose-50 text-rose-700" : "bg-emerald-700 text-white"}`}>{service.active ? localize("Ngừng", "Deactivate") : localize("Kích hoạt", "Activate")}</button>
                </div>
              </div>
            </article>
          ))}
        </div>
      )}

      <ViewportModal open={isFormOpen} onClose={closeForm} labelledBy="service-form-title" busy={isSaving || isUploading} panelClassName="max-w-4xl" testId="service-form-modal">
        <form onSubmit={save} className="flex min-h-0 flex-1 flex-col">
          <header className="border-b border-[#0F2A43]/10 bg-[#0F2A43] px-6 py-5 text-white">
            <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#D8C398]">{editing ? localize("Chỉnh sửa catalog", "Edit catalog") : localize("Dịch vụ mới", "New service")}</p>
            <h2 id="service-form-title" className="mt-1 font-serif text-2xl font-bold">{localize("Thông tin dịch vụ thêm", "Add-on service details")}</h2>
          </header>
          <div className="lux-scrollbar min-h-0 flex-1 overflow-y-auto p-5 sm:p-6">
            <div className="grid gap-5 lg:grid-cols-[18rem_1fr]">
              <ImageUploadField
                folder="ADD_ON_SERVICES"
                value={form.imageUrl}
                label={localize("Ảnh dịch vụ", "Service image")}
                alt={form.name || localize("Ảnh dịch vụ", "Service image")}
                description={localize("JPEG, PNG hoặc WebP, tối đa 5 MB.", "JPEG, PNG or WebP, up to 5 MB.")}
                aspect="landscape"
                disabled={isSaving}
                onUploadingChange={setIsUploading}
                onUploaded={(image) => setForm((current) => ({ ...current, imageUrl: image.url }))}
              />
              <div className="grid min-w-0 gap-4 sm:grid-cols-2">
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43]">{localize("Mã dịch vụ", "Service code")} *<input value={form.code} maxLength={64} disabled={Boolean(editing)} onChange={(event) => { setForm({ ...form, code: event.target.value.toUpperCase() }); setFormErrors((current) => ({ ...current, code: "" })); }} className="min-h-11 rounded-lg border px-3 text-sm uppercase outline-none focus:border-[#B8944F] disabled:bg-[#E5E9ED]" />{formErrors.code && <span className="text-xs text-rose-700">{formErrors.code}</span>}</label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43]">{localize("Phân loại", "Category")} *<select value={form.category} onChange={(event) => setForm({ ...form, category: event.target.value as AddOnServiceCategory })} className="min-h-11 rounded-lg border bg-white px-3 text-sm outline-none focus:border-[#B8944F]">{categories.map((category) => <option key={category} value={category}>{categoryLabel(category)}</option>)}</select></label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43]">{localize("Tên (VI)", "Name (VI)")} *<input value={form.name} maxLength={255} onChange={(event) => { setForm({ ...form, name: event.target.value }); setFormErrors((current) => ({ ...current, name: "" })); }} className="min-h-11 rounded-lg border px-3 text-sm outline-none focus:border-[#B8944F]" />{formErrors.name && <span className="text-xs text-rose-700">{formErrors.name}</span>}</label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43]">{localize("Tên (EN)", "Name (EN)")}<input value={form.nameEn} maxLength={255} onChange={(event) => setForm({ ...form, nameEn: event.target.value })} className="min-h-11 rounded-lg border px-3 text-sm outline-none focus:border-[#B8944F]" />{formErrors.nameEn && <span className="text-xs text-rose-700">{formErrors.nameEn}</span>}</label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43]">{localize("Giá (VND)", "Price (VND)")} *<input inputMode="numeric" value={form.price} onChange={(event) => { setForm({ ...form, price: event.target.value.replace(/\D/g, "") }); setFormErrors((current) => ({ ...current, price: "" })); }} className="min-h-11 rounded-lg border px-3 text-right text-sm font-bold outline-none focus:border-[#B8944F]" />{formErrors.price && <span className="text-xs text-rose-700">{formErrors.price}</span>}</label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43]">{localize("Đơn vị tính", "Pricing unit")} *<select value={form.pricingUnit} onChange={(event) => setForm({ ...form, pricingUnit: event.target.value as AddOnPricingUnit })} className="min-h-11 rounded-lg border bg-white px-3 text-sm outline-none focus:border-[#B8944F]">{pricingUnits.map((unit) => <option key={unit} value={unit}>{unitLabel(unit)}</option>)}</select></label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43] sm:col-span-2">{localize("Mô tả (VI)", "Description (VI)")}<textarea rows={3} maxLength={2000} value={form.description} onChange={(event) => setForm({ ...form, description: event.target.value })} className="resize-none rounded-lg border px-3 py-2 text-sm outline-none focus:border-[#B8944F]" />{formErrors.description && <span className="text-xs text-rose-700">{formErrors.description}</span>}</label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43] sm:col-span-2">{localize("Mô tả (EN)", "Description (EN)")}<textarea rows={3} maxLength={2000} value={form.descriptionEn} onChange={(event) => setForm({ ...form, descriptionEn: event.target.value })} className="resize-none rounded-lg border px-3 py-2 text-sm outline-none focus:border-[#B8944F]" /></label>
                <label className="grid gap-1.5 text-xs font-bold text-[#0F2A43]">{localize("Thứ tự hiển thị", "Sort order")}<input type="number" min={0} max={100000} value={form.sortOrder} onChange={(event) => { setForm({ ...form, sortOrder: event.target.value }); setFormErrors((current) => ({ ...current, sortOrder: "" })); }} className="min-h-11 rounded-lg border px-3 text-sm outline-none focus:border-[#B8944F]" />{formErrors.sortOrder && <span className="text-xs text-rose-700">{formErrors.sortOrder}</span>}</label>
                <div className="grid gap-2 sm:grid-cols-2">
                  <label className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border px-3 text-xs font-bold text-[#0F2A43]"><input type="checkbox" checked={form.bookingEnabled} onChange={(event) => setForm({ ...form, bookingEnabled: event.target.checked })} className="h-4 w-4 accent-[#0F2A43]" />{localize("Cho phép đặt trước", "Booking-time")}</label>
                  <label className="flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border px-3 text-xs font-bold text-[#0F2A43]"><input type="checkbox" checked={form.inStayEnabled} onChange={(event) => setForm({ ...form, inStayEnabled: event.target.checked })} className="h-4 w-4 accent-[#0F2A43]" />{localize("Cho phép trong kỳ lưu trú", "In-stay")}</label>
                </div>
              </div>
            </div>
          </div>
          <footer className="flex flex-col-reverse gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-6 py-4 sm:flex-row sm:justify-end">
            <button type="button" disabled={isSaving || isUploading} onClick={closeForm} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">{localize("Hủy", "Cancel")}</button>
            <button type="submit" disabled={isSaving || isUploading} className="inline-flex min-h-11 min-w-36 items-center justify-center gap-2 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:opacity-50">{isSaving && <span className="h-4 w-4 animate-spin rounded-full border-2 border-white/30 border-r-white" />}{isUploading ? localize("Đang tải ảnh...", "Uploading...") : isSaving ? localize("Đang lưu...", "Saving...") : localize("Lưu dịch vụ", "Save service")}</button>
          </footer>
        </form>
      </ViewportModal>

      <ViewportModal open={Boolean(activationTarget)} onClose={() => setActivationTarget(null)} labelledBy="service-activation-title" busy={isSaving} panelClassName="max-w-lg">
        {activationTarget && <section className="p-6"><h2 id="service-activation-title" className="font-serif text-2xl font-bold text-[#0F2A43]">{activationTarget.active ? localize("Ngừng cung cấp dịch vụ?", "Deactivate this service?") : localize("Kích hoạt lại dịch vụ?", "Reactivate this service?")}</h2><p className="mt-3 text-sm leading-6 text-[#66727C]">{activationTarget.active ? localize("Dịch vụ sẽ không còn xuất hiện cho yêu cầu mới; các dòng đã snapshot trong đơn cũ vẫn được giữ nguyên.", "The service disappears from new requests; existing reservation snapshots remain unchanged.") : localize("Dịch vụ sẽ xuất hiện lại ở những luồng đã bật.", "The service will return to its enabled flows.")}</p><div className="mt-6 flex justify-end gap-3"><button type="button" disabled={isSaving} onClick={() => setActivationTarget(null)} className="min-h-11 rounded-lg border px-5 text-sm font-bold">{localize("Quay lại", "Back")}</button><button type="button" disabled={isSaving} onClick={() => void changeActivation()} className={`min-h-11 rounded-lg px-5 text-sm font-bold text-white ${activationTarget.active ? "bg-rose-700" : "bg-emerald-700"}`}>{isSaving ? localize("Đang xử lý...", "Processing...") : activationTarget.active ? localize("Ngừng cung cấp", "Deactivate") : localize("Kích hoạt", "Activate")}</button></div></section>}
      </ViewportModal>

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
