"use client";

import React, { useCallback, useState, useEffect, useMemo } from "react";
import { apiClient, cachedGet } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import AddGuestModal from "@/components/modals/AddGuestModal";
import EditGuestModal from "@/components/modals/EditGuestModal";
import DeleteGuestModal from "@/components/modals/DeleteGuestModal";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

interface UserItem {
  id: number;
  fullName: string;
  username: string;
  email: string;
  phone: string;
  address: string;
  type: "CUSTOMER" | "STAFF" | "ADMIN";
  status: "ACTIVE" | "INACTIVE";
  imageUrl?: string;
}

interface UserFormData {
  fullName: string;
  username: string;
  email: string;
  phone: string;
  address: string;
  type: UserItem["type"];
  password: string;
  confirmPassword?: string;
}

export default function UsersManagement() {
  const { isAdmin, role } = useDashboardRole();
  const { localize } = useLanguage();
  const [users, setUsers] = useState<UserItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedFilter, setSelectedFilter] = useState<"All" | "ADMIN" | "STAFF" | "CUSTOMER">("All");
  const [accountStatusFilter, setAccountStatusFilter] = useState<"ALL" | UserItem["status"]>("ALL");

  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isEditOpen, setIsEditOpen] = useState(false);
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);
  const [selectedUser, setSelectedUser] = useState<UserItem | null>(null);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const showToast = useCallback((message: string, type: "success" | "error" | "info") => {
    setToast({ message, type });
  }, []);

  const fetchUsers = useCallback(async (showLoading = false) => {
    if (showLoading) setIsLoading(true);
    try {
      const res = await cachedGet("/api/user/list?size=100");
      if (res.data && res.data.data && res.data.data.users) {
        setUsers(res.data.data.users);
      }
    } catch {
      showToast("Không thể tải danh sách người dùng từ backend", "error");
    } finally {
      setIsLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void fetchUsers(true);
    const refreshInBackground = () => void fetchUsers(false);
    window.addEventListener("focus", refreshInBackground);
    return () => window.removeEventListener("focus", refreshInBackground);
  }, [fetchUsers]);

  const filteredUsers = useMemo(() => {
    return users.filter((u) => {
      const matchesSearch =
        u.fullName.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.email.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.phone.includes(searchQuery) ||
        (u.address && u.address.toLowerCase().includes(searchQuery.toLowerCase()));

      let matchesFilter = true;
      if (selectedFilter !== "All") {
        matchesFilter = u.type === selectedFilter;
      }

      const matchesAccountStatus = accountStatusFilter === "ALL" || u.status === accountStatusFilter;
      return matchesSearch && matchesFilter && matchesAccountStatus;
    });
  }, [accountStatusFilter, users, searchQuery, selectedFilter]);

  const handleCreateConfirm = async (data: UserFormData) => {
    await apiClient.post("/api/user", {
      fullName: data.fullName,
      username: data.username,
      email: data.email,
      phone: data.phone,
      address: data.address,
      type: data.type,
      password: data.password,
    });

    showToast("Tạo người dùng thành công", "success");
    setIsCreateOpen(false);
    await fetchUsers();
  };

  const handleEditConfirm = async (id: number, data: UserFormData) => {
    await apiClient.put(`/api/user/${id}`, {
      fullName: data.fullName,
      username: data.username,
      email: data.email,
      phone: data.phone,
      address: data.address,
      type: data.type,
    });

    if (data.password) {
      await apiClient.patch(`/api/user/${id}/reset-password`, {
        password: data.password,
        confirmPassword: data.confirmPassword,
      });
    }

    showToast(data.password ? "Đã cập nhật người dùng và đặt lại mật khẩu" : "Cập nhật người dùng thành công", "success");
    setIsEditOpen(false);
    setSelectedUser(null);
    await fetchUsers();
  };

  const handleDeleteConfirm = async () => {
    if (!selectedUser) return;
    await apiClient.delete(`/api/user/${selectedUser.id}`);
    showToast("Xóa người dùng thành công", "success");
    setIsDeleteOpen(false);
    setSelectedUser(null);
    await fetchUsers();
  };

  const totalCount = users.length;
  const adminCount = users.filter((u) => u.type === "ADMIN").length;
  const staffCount = users.filter((u) => u.type === "STAFF").length;
  const customerCount = users.filter((u) => u.type === "CUSTOMER").length;

  return (
    <div className="ops-page mx-auto w-full max-w-[1600px] space-y-10 p-6 md:p-10">
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4 pb-4 border-b border-[#0F2A43]/5">
        <div>
          <h1 className="font-serif text-3xl md:text-4xl font-bold tracking-tight text-[#0F2A43] leading-tight">{localize("Quản lý người dùng", "User management")}</h1>
          <p className="text-xs text-[#66727C] mt-1.5 font-bold uppercase tracking-wider">{localize(`${totalCount} tài khoản trong hệ thống`, `${totalCount} accounts in the system`)}</p>
        </div>
        {isAdmin ? (
          <button
            onClick={() => setIsCreateOpen(true)}
            className="self-start sm:self-auto px-5 py-2.5 bg-[#0F2A43] hover:bg-[#091E30] text-white font-semibold text-sm rounded-lg shadow-sm transition-all duration-300 flex items-center gap-2"
          >
            <span>+</span> {localize("Thêm người dùng", "Add user")}
          </button>
        ) : role === "STAFF" ? (
          <span className="rounded-lg border border-[#B8944F]/30 bg-[#F0EADF] px-4 py-2 text-xs font-bold text-[#80632F]">{localize("Chế độ chỉ xem", "Read-only mode")}</span>
        ) : null}
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-8">
        <div className="bg-white p-6 rounded-xl border border-[#0F2A43]/10 shadow-sm">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-[#F0EADF] text-[#B8944F] rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{totalCount}</span>
              <span className="block text-[9px] tracking-wider uppercase font-bold text-[#66727C] mt-1">{localize("Tổng người dùng", "Total users")}</span>
            </div>
          </div>
        </div>

        <div className="bg-white p-6 rounded-xl border border-[#0F2A43]/10 shadow-sm">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-rose-50 text-rose-600 rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{adminCount}</span>
              <span className="block text-[9px] tracking-wider uppercase font-bold text-[#66727C] mt-1">{localize("Quản trị viên", "Administrators")}</span>
            </div>
          </div>
        </div>

        <div className="bg-white p-6 rounded-xl border border-[#0F2A43]/10 shadow-sm">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-blue-50 text-blue-600 rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{staffCount}</span>
              <span className="block text-[9px] tracking-wider uppercase font-bold text-[#66727C] mt-1">{localize("Nhân viên", "Staff")}</span>
            </div>
          </div>
        </div>

        <div className="bg-white p-6 rounded-xl border border-[#0F2A43]/10 shadow-sm">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-[#F0EADF] text-[#B8944F] rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
                <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{customerCount}</span>
              <span className="block text-[9px] tracking-wider uppercase font-bold text-[#66727C] mt-1">{localize("Khách hàng", "Customers")}</span>
            </div>
          </div>
        </div>
      </div>

      <div className="space-y-6">
        <DashboardFilterPanel
          title={localize("Bộ lọc tài khoản", "Account filters")}
          description={localize("Tra cứu theo thông tin liên hệ, vai trò và trạng thái tài khoản", "Search by contact details, role and account status")}
          resultCount={filteredUsers.length}
          resultLabel={localize("tài khoản phù hợp", "matching accounts")}
          resultNote={localize(`${users.length} tài khoản trong hệ thống`, `${users.length} accounts in the system`)}
          hasActiveFilters={Boolean(searchQuery || selectedFilter !== "All" || accountStatusFilter !== "ALL")}
          activeFilterCount={Number(Boolean(searchQuery)) + Number(selectedFilter !== "All") + Number(accountStatusFilter !== "ALL")}
          activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
          onReset={() => {
            setSearchQuery("");
            setSelectedFilter("All");
            setAccountStatusFilter("ALL");
          }}
          resetLabel={localize("Xóa toàn bộ bộ lọc", "Clear all filters")}
          actions={(
            <>
              <FilterQuickButton active={selectedFilter === "STAFF"} onClick={() => setSelectedFilter("STAFF")}>{localize("Nhân viên", "Staff")}</FilterQuickButton>
              <FilterQuickButton active={selectedFilter === "CUSTOMER"} onClick={() => setSelectedFilter("CUSTOMER")}>{localize("Khách hàng", "Customers")}</FilterQuickButton>
            </>
          )}
        >
          <div className="grid gap-4 md:grid-cols-[minmax(0,2fr)_minmax(12rem,1fr)_minmax(12rem,1fr)]">
            <DashboardSearchField
              id="user-search"
              label={localize("Tìm kiếm", "Search")}
              value={searchQuery}
              onChange={setSearchQuery}
              placeholder={localize("Tên, email, số điện thoại hoặc địa chỉ...", "Name, email, phone or address...")}
              clearLabel={localize("Xóa từ khóa", "Clear search")}
            />
            <DashboardSelectField id="user-role" label={localize("Vai trò", "Role")} value={selectedFilter} onChange={(event) => setSelectedFilter(event.target.value as typeof selectedFilter)}>
              <option value="All">{localize("Tất cả vai trò", "All roles")}</option>
              <option value="ADMIN">Admin</option>
              <option value="STAFF">Staff</option>
              <option value="CUSTOMER">Customer</option>
            </DashboardSelectField>
            <DashboardSelectField id="user-status" label={localize("Trạng thái", "Status")} value={accountStatusFilter} onChange={(event) => setAccountStatusFilter(event.target.value as typeof accountStatusFilter)}>
              <option value="ALL">{localize("Tất cả trạng thái", "All statuses")}</option>
              <option value="ACTIVE">{localize("Đang hoạt động", "Active")}</option>
              <option value="INACTIVE">{localize("Đã khóa", "Inactive")}</option>
            </DashboardSelectField>
          </div>
        </DashboardFilterPanel>

        {isLoading ? (
          <div className="text-center py-12 text-[#66727C] font-semibold text-sm">
            {localize("Đang tải dữ liệu người dùng...", "Loading user data...")}
          </div>
        ) : filteredUsers.length === 0 ? (
          <div className="bg-white text-center py-12 border-2 border-dashed border-[#0F2A43]/10 rounded-xl text-[#66727C] font-semibold text-sm">
            {localize("Không tìm thấy người dùng phù hợp.", "No users match the selected filters.")}
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
            {filteredUsers.map((user) => {
              const initials = user.fullName.split(" ").map((n) => n[0]).join("").toUpperCase().slice(0, 2);
              return (
                <div key={user.id} className="bg-white border border-[#0F2A43]/10 rounded-xl p-6 shadow-sm flex flex-col justify-between hover:border-[#B8944F]/40 transition-all duration-300">
                  <div>
                    <div className="flex justify-between items-start">
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-full bg-[#F0EADF] border border-[#F0EADF] text-[#B8944F] flex items-center justify-center font-serif font-bold text-sm">
                          {initials}
                        </div>
                        <div>
                          <h4 className="font-serif text-base font-bold text-[#0F2A43]">{user.fullName}</h4>
                          <span className="text-[10px] text-[#66727C] font-bold uppercase tracking-wider">
                            @{user.username}
                          </span>
                        </div>
                      </div>

                      <span className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded text-[10px] font-bold ${
                        user.type === "ADMIN" ? "bg-rose-50 text-rose-600 border border-rose-100" :
                        user.type === "STAFF" ? "bg-blue-50 text-blue-600 border border-blue-100" :
                        "bg-emerald-50 text-emerald-600 border border-emerald-100"
                      }`}>
                        {user.type}
                      </span>
                    </div>

                    <div className="mt-5 space-y-2 text-xs text-[#66727C] font-semibold">
                      <div className="flex items-center gap-2.5">
                        <svg
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          className="w-3.5 h-3.5 text-[#B8944F]"
                        >
                          <rect width="20" height="14" x="2" y="5" rx="2" />
                          <path d="m22 7-8.97 5.7a1.94 1.94 0 0 1-2.06 0L2 7" />
                        </svg>
                        <span>{user.email}</span>
                      </div>
                      <div className="flex items-center gap-2.5">
                        <svg
                          viewBox="0 0 24 24"
                          fill="none"
                          stroke="currentColor"
                          strokeWidth="2"
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          className="w-3.5 h-3.5 text-[#B8944F]"
                        >
                          <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z" />
                        </svg>
                        <span>{user.phone}</span>
                      </div>
                      {user.address && (
                        <div className="flex items-center gap-2.5">
                          <svg
                            viewBox="0 0 24 24"
                            fill="none"
                            stroke="currentColor"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                            className="w-3.5 h-3.5 text-[#B8944F]"
                          >
                            <path d="M20 10c0 6-8 12-8 12s-8-6-8-12a8 8 0 0 1 16 0Z" />
                            <circle cx="12" cy="10" r="3" />
                          </svg>
                          <span>{user.address}</span>
                        </div>
                      )}
                    </div>
                  </div>

                  {isAdmin && <div className="mt-6 pt-4 border-t border-[#0F2A43]/5 flex justify-end gap-3">
                    <button
                      onClick={() => {
                        setSelectedUser(user);
                        setIsEditOpen(true);
                      }}
                      className="px-3.5 py-1.5 border border-gray-200 hover:border-[#B8944F] text-[#66727C] hover:text-[#B8944F] text-xs font-bold rounded-lg transition-colors"
                    >
                    {localize("Chỉnh sửa", "Edit")}
                    </button>
                    <button
                      onClick={() => {
                        setSelectedUser(user);
                        setIsDeleteOpen(true);
                      }}
                      className="px-3.5 py-1.5 border border-red-200 hover:bg-red-50 text-red-600 text-xs font-bold rounded-lg transition-colors"
                    >
                      {localize("Vô hiệu hóa", "Deactivate")}
                    </button>
                  </div>}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {isAdmin && <AddGuestModal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        onConfirm={handleCreateConfirm}
      />}

      {isAdmin && <EditGuestModal
        isOpen={isEditOpen}
        onClose={() => {
          setIsEditOpen(false);
          setSelectedUser(null);
        }}
        user={selectedUser}
        onConfirm={handleEditConfirm}
      />}

      {isAdmin && <DeleteGuestModal
        isOpen={isDeleteOpen}
        onClose={() => {
          setIsDeleteOpen(false);
          setSelectedUser(null);
        }}
        user={selectedUser}
        onConfirm={handleDeleteConfirm}
      />}

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
