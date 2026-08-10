"use client";

import React, { useCallback, useState, useEffect, useLayoutEffect, useMemo, useRef } from "react";
import { createPortal } from "react-dom";
import dynamic from "next/dynamic";
import { useRouter } from "next/navigation";
import { apiClient, cachedGet } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import Button from "@/components/UI/Button";
import ViewportModal from "@/components/UI/ViewportModal";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { useDashboardRole } from "@/hooks/use-dashboard-role";
import { getPublicRoomTypes } from "@/lib/public-catalog";
import {
  getRoomOperationalState,
  summarizeRoomOperations,
  type RoomOperationalState,
} from "@/lib/room-operational-state";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

const CleanConfirmModal = dynamic(() => import("@/components/modals/CleanConfirmModal"), { ssr: false });
const MaintenanceFormModal = dynamic(() => import("@/components/modals/MaintenanceFormModal"), { ssr: false });
const MaintenanceDetailModal = dynamic(() => import("@/components/modals/MaintenanceDetailModal"), { ssr: false });

interface RoomType {
  id: number;
  typeName: string;
  typeNameEn?: string;
  pricingAvailable?: boolean;
  overnightPrice?: number | null;
  dailyPrice?: number | null;
  description: string;
}

interface RoomItem {
  id: number;
  roomName: string;
  floor: number;
  status: "AVAILABLE" | "BOOKED" | "CHECKED_IN" | "MAINTENANCE";
  cleaningStatus: "CLEAN" | "DIRTY" | "IN_PROGRESS";
  description: string;
  roomTypeId: number;
  roomTypeName: string;
  roomTypeNameEn?: string;
  maintenanceReason?: string;
  maintenanceExpectedCompletedDate?: string;
  maintenanceHistory?: { date: string; action: string; note: string }[];
}

interface ConfirmedReservation {
  id: number;
  reservationCode: string;
  customerName?: string;
  guestCount?: number;
  checkIn: string;
  checkOut: string;
  status: string;
  roomTypes?: Array<{
    id: number;
    roomTypeId: number;
    roomTypeName: string;
    roomTypeNameEn?: string;
    quantity: number;
  }>;
}

const getApiErrorMessage = (error: unknown, fallback: string) => {
  if (typeof error !== "object" || error === null || !("response" in error)) return fallback;
  const response = (error as { response?: { data?: { message?: unknown } } }).response;
  return typeof response?.data?.message === "string" ? response.data.message : fallback;
};

const formatReservationTime = (value: string, localeTag: string) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString(localeTag, {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
};

const ROOM_OPERATIONAL_STYLES: Record<RoomOperationalState, {
  tile: string;
  card: string;
  badge: string;
  dot: string;
  control: string;
}> = {
  READY: {
    tile: "border-emerald-300 bg-emerald-50/70 text-emerald-900 hover:bg-emerald-50",
    card: "border-emerald-200/80 bg-emerald-50/25 hover:border-emerald-300",
    badge: "border-emerald-200 bg-emerald-50 text-emerald-800",
    dot: "bg-emerald-500",
    control: "border-emerald-300 bg-emerald-50 text-emerald-800",
  },
  NEEDS_CLEANING: {
    tile: "border-orange-300 bg-orange-50/80 text-orange-900 hover:bg-orange-50",
    card: "border-orange-200 bg-orange-50/35 hover:border-orange-300",
    badge: "border-orange-200 bg-orange-50 text-orange-800",
    dot: "bg-orange-500",
    control: "border-orange-300 bg-orange-50 text-orange-800",
  },
  CLEANING: {
    tile: "border-violet-300 bg-violet-50/75 text-violet-900 hover:bg-violet-50",
    card: "border-violet-200 bg-violet-50/30 hover:border-violet-300",
    badge: "border-violet-200 bg-violet-50 text-violet-800",
    dot: "bg-violet-500",
    control: "border-violet-300 bg-violet-50 text-violet-800",
  },
  RESERVED: {
    tile: "border-amber-300 bg-amber-50/75 text-amber-900 hover:bg-amber-50",
    card: "border-amber-200 bg-amber-50/30 hover:border-amber-300",
    badge: "border-amber-200 bg-amber-50 text-amber-800",
    dot: "bg-amber-500",
    control: "border-amber-300 bg-amber-50 text-amber-800",
  },
  OCCUPIED: {
    tile: "border-sky-300 bg-sky-50/75 text-sky-900 hover:bg-sky-50",
    card: "border-sky-200 bg-sky-50/30 hover:border-sky-300",
    badge: "border-sky-200 bg-sky-50 text-sky-800",
    dot: "bg-sky-500",
    control: "border-sky-300 bg-sky-50 text-sky-800",
  },
  MAINTENANCE: {
    tile: "border-rose-300 bg-rose-50/80 text-rose-900 hover:bg-rose-50",
    card: "border-rose-200 bg-rose-50/35 hover:border-rose-300",
    badge: "border-rose-200 bg-rose-50 text-rose-800",
    dot: "bg-rose-500",
    control: "border-rose-300 bg-rose-50 text-rose-800",
  },
};

export default function RoomsManagement() {
  const router = useRouter();
  const { localeTag, localize } = useLanguage();
  const { isAdmin } = useDashboardRole();
  const [rooms, setRooms] = useState<RoomItem[]>([]);
  const [roomTypes, setRoomTypes] = useState<RoomType[]>([]);
  const [confirmedReservations, setConfirmedReservations] = useState<ConfirmedReservation[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedStatus, setSelectedStatus] = useState<"All" | "AVAILABLE" | "BOOKED" | "CHECKED_IN" | "MAINTENANCE">("All");
  const [selectedType, setSelectedType] = useState<string>("All");

  // US4.4 Sơ đồ tầng & Trạng thái buồng phòng
  const [viewMode, setViewMode] = useState<"floor" | "list">("floor");
  const [cleaningFilter, setCleaningFilter] = useState({
    CLEAN: true,
    DIRTY: true,
    IN_PROGRESS: true,
  });

  // Context Menu
  const [contextMenu, setContextMenu] = useState<{
    roomId: number;
    roomName: string;
    status: RoomItem["status"];
    cleaningStatus: RoomItem["cleaningStatus"];
    x: number;
    y: number;
  } | null>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const menu = contextMenuRef.current;
    if (!contextMenu || !menu) return;

    const viewportPadding = 12;
    const bounds = menu.getBoundingClientRect();
    let nextX = contextMenu.x;
    let nextY = contextMenu.y;

    if (nextX + bounds.width > window.innerWidth - viewportPadding) {
      nextX = Math.max(viewportPadding, window.innerWidth - viewportPadding - bounds.width);
    }
    if (nextY + bounds.height > window.innerHeight - viewportPadding) {
      nextY = Math.max(viewportPadding, window.innerHeight - viewportPadding - bounds.height);
    }

    if (nextX !== contextMenu.x || nextY !== contextMenu.y) {
      setContextMenu((current) => current ? { ...current, x: nextX, y: nextY } : current);
    }
  }, [contextMenu]);

  // Swap Room modal state
  const [isSwapOpen, setIsSwapOpen] = useState(false);
  const [swapSourceRoom, setSwapSourceRoom] = useState<RoomItem | null>(null);
  const [swapTargetRoomName, setSwapTargetRoomName] = useState("");
  const [isSwapLoading, setIsSwapLoading] = useState(false);
  const [checkoutRoomId, setCheckoutRoomId] = useState<number | null>(null);
  const [checkoutConfirmation, setCheckoutConfirmation] = useState<{ reservationId: number; reservationCode: string; roomCount: number } | null>(null);

  // US: Housekeeping & Maintenance state variables
  const [confirmCleanOpen, setConfirmCleanOpen] = useState(false);
  const [cleanTargetRoom, setCleanTargetRoom] = useState<RoomItem | null>(null);
  const [cleanTargetStatus, setCleanTargetStatus] = useState<RoomItem["cleaningStatus"]>("CLEAN");

  const [maintenanceFormOpen, setMaintenanceFormOpen] = useState(false);
  const [maintenanceTargetRoom, setMaintenanceTargetRoom] = useState<RoomItem | null>(null);

  const [maintenanceDetailOpen, setMaintenanceDetailOpen] = useState(false);
  const [maintenanceDetailRoom, setMaintenanceDetailRoom] = useState<RoomItem | null>(null);

  // Modals
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isEditOpen, setIsEditOpen] = useState(false);
  const [isDeleteOpen, setIsDeleteOpen] = useState(false);
  const [roomMutation, setRoomMutation] = useState<"create" | "edit" | "delete" | null>(null);
  const [roomFormError, setRoomFormError] = useState("");

  // Form states
  const [formData, setFormData] = useState({
    id: 0,
    roomName: "",
    roomTypeId: 0,
    floor: 1,
    description: "",
  });
  const [roomNameAutoFilled, setRoomNameAutoFilled] = useState(false);

  const [selectedRoom, setSelectedRoom] = useState<RoomItem | null>(null);

  // Toast
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const showToast = useCallback((message: string, type: "success" | "error" | "info") => {
    setToast({ message, type });
  }, []);

  // Fetch Rooms & Room Types
  const fetchData = useCallback(async (showLoading = false) => {
    if (showLoading) setIsLoading(true);
    try {
      const [roomsResult, typesResult, reservationsResult] = await Promise.allSettled([
        cachedGet("/api/rooms"),
        getPublicRoomTypes<RoomType>(),
        cachedGet("/api/reservations"),
      ]);

      if (roomsResult.status === "fulfilled" && Array.isArray(roomsResult.value.data)) {
        const enriched = (roomsResult.value.data as RoomItem[]).map((room) => ({
          ...room,
          maintenanceHistory: room.maintenanceHistory || []
        }));
        setRooms(enriched);
      } else {
        setRooms([]);
      }
      if (typesResult.status === "fulfilled") {
        setRoomTypes(typesResult.value);
      } else {
        setRoomTypes([]);
      }
      const reservationData = reservationsResult.status === "fulfilled"
        ? reservationsResult.value.data?.data ?? reservationsResult.value.data
        : [];
      setConfirmedReservations(
        (Array.isArray(reservationData) ? reservationData : [])
          .filter((reservation: ConfirmedReservation) => reservation.status === "CONFIRMED")
          .sort((left: ConfirmedReservation, right: ConfirmedReservation) =>
            new Date(left.checkIn).getTime() - new Date(right.checkIn).getTime(),
          ),
      );
      if ([roomsResult, typesResult, reservationsResult].some((result) => result.status === "rejected")) {
        showToast("Một phần dữ liệu vận hành chưa tải được. Các khu vực còn lại vẫn được giữ.", "error");
      }
    } catch {
      showToast("Không thể tải danh sách phòng từ backend.", "error");
      setRooms([]);
      setRoomTypes([]);
      setConfirmedReservations([]);
    } finally {
      setIsLoading(false);
    }
  }, [showToast]);

  useEffect(() => {
    void fetchData(true);
    const refreshInBackground = () => void fetchData(false);
    window.addEventListener("focus", refreshInBackground);
    return () => window.removeEventListener("focus", refreshInBackground);
  }, [fetchData]);

  // Click outside to close context menu
  useEffect(() => {
    const handleOutsideClick = () => {
      setContextMenu(null);
    };
    window.addEventListener("click", handleOutsideClick);
    return () => window.removeEventListener("click", handleOutsideClick);
  }, []);

  // Filter & Search Logic
  const filteredRooms = useMemo(() => {
    return rooms.filter((room) => {
      const matchesSearch =
        room.roomName.toLowerCase().includes(searchQuery.toLowerCase()) ||
        (room.description && room.description.toLowerCase().includes(searchQuery.toLowerCase())) ||
        room.roomTypeName.toLowerCase().includes(searchQuery.toLowerCase());

      const matchesStatus = selectedStatus === "All" || room.status === selectedStatus;
      const matchesType = selectedType === "All" || String(room.roomTypeId) === selectedType;

      // AC-GRID-02: filter by cleaning status
      const matchesCleaning = cleaningFilter[room.cleaningStatus || "CLEAN"];

      return matchesSearch && matchesStatus && matchesType && matchesCleaning;
    });
  }, [rooms, searchQuery, selectedStatus, selectedType, cleaningFilter]);

  const roomTypeById = useMemo(
    () => new Map(roomTypes.map((roomType) => [roomType.id, roomType])),
    [roomTypes],
  );

  // Group rooms by floor (US4.4 Floor Matrix)
  const roomsByFloor = useMemo(() => {
    const floorsMap: { [key: number]: RoomItem[] } = {};
    filteredRooms.forEach((room) => {
      if (!floorsMap[room.floor]) {
        floorsMap[room.floor] = [];
      }
      floorsMap[room.floor].push(room);
    });

    const sortedFloors = Object.keys(floorsMap)
      .map(Number)
      .sort((a, b) => a - b);

    return sortedFloors.map((floorNum) => ({
      floorNum,
      rooms: floorsMap[floorNum].sort((a, b) => a.roomName.localeCompare(b.roomName)),
    }));
  }, [filteredRooms]);

  // Init Create
  const suggestRoomName = useCallback((floor: number) => {
    const normalizedFloor = Math.max(1, Number(floor) || 1);
    const numbersOnFloor = rooms
      .filter((room) => Number(room.floor) === normalizedFloor)
      .map((room) => room.roomName.match(/(\d+)\s*$/)?.[1])
      .filter(Boolean)
      .map(Number)
      .filter(Number.isFinite);
    const nextNumber = numbersOnFloor.length
      ? Math.max(...numbersOnFloor) + 1
      : normalizedFloor * 100 + 1;
    return localize(`Phòng ${nextNumber}`, `Room ${nextNumber}`);
  }, [localize, rooms]);

  const openCreateModal = () => {
    if (!isAdmin) return;
    const defaultFloor = 1;
    setFormData({
      id: 0,
      roomName: suggestRoomName(defaultFloor),
      roomTypeId: roomTypes[0]?.id || 0,
      floor: defaultFloor,
      description: "",
    });
    setRoomNameAutoFilled(true);
    setRoomFormError("");
    setIsCreateOpen(true);
  };

  // Submit Create
  const handleCreateSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.roomName.trim() || !formData.roomTypeId || !formData.floor) {
      setRoomFormError(localize("Vui lòng nhập đầy đủ tên phòng, loại phòng và tầng.", "Enter the room name, room type, and floor."));
      return;
    }

    setRoomMutation("create");
    setRoomFormError("");
    try {
      await apiClient.post("/api/rooms", {
        roomName: formData.roomName,
        roomTypeId: formData.roomTypeId,
        floor: Number(formData.floor),
        description: formData.description,
      });

      showToast("Thêm phòng mới thành công!", "success");
      setIsCreateOpen(false);
      fetchData();
    } catch (error: unknown) {
      const errMsg = getApiErrorMessage(error, "Không thể tạo phòng mới.");
      setRoomFormError(errMsg);
    } finally {
      setRoomMutation(null);
    }
  };

  // Init Edit
  const openEditModal = (room: RoomItem) => {
    if (!isAdmin) return;
    setSelectedRoom(room);
    setFormData({
      id: room.id,
      roomName: room.roomName,
      roomTypeId: room.roomTypeId,
      floor: room.floor,
      description: room.description || "",
    });
    setRoomNameAutoFilled(false);
    setRoomFormError("");
    setIsEditOpen(true);
  };

  // Submit Edit
  const handleEditSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.roomName.trim() || !formData.roomTypeId || !formData.floor) {
      setRoomFormError(localize("Vui lòng nhập đầy đủ các trường bắt buộc.", "Complete all required fields."));
      return;
    }

    setRoomMutation("edit");
    setRoomFormError("");
    try {
      await apiClient.put(`/api/rooms/${formData.id}`, {
        roomName: formData.roomName,
        roomTypeId: formData.roomTypeId,
        floor: Number(formData.floor),
        description: formData.description,
      });

      showToast("Cập nhật phòng thành công!", "success");
      setIsEditOpen(false);
      fetchData();
    } catch (error: unknown) {
      const errMsg = getApiErrorMessage(error, "Không thể cập nhật phòng.");
      setRoomFormError(errMsg);
    } finally {
      setRoomMutation(null);
    }
  };

  // Init Delete
  const openDeleteModal = (room: RoomItem) => {
    if (!isAdmin) return;
    setSelectedRoom(room);
    setRoomFormError("");
    setIsDeleteOpen(true);
  };

  // Confirm Delete
  const handleDeleteConfirm = async () => {
    if (!selectedRoom) return;
    setRoomMutation("delete");
    setRoomFormError("");
    try {
      await apiClient.delete(`/api/rooms/${selectedRoom.id}`);
      showToast("Xóa phòng thành công!", "success");
      setIsDeleteOpen(false);
      fetchData();
    } catch (error: unknown) {
      const errMsg = getApiErrorMessage(error, "Không thể xóa phòng này.");
      setRoomFormError(errMsg);
    } finally {
      setRoomMutation(null);
    }
  };

  // Update Status directly
  const handleStatusUpdate = async (roomId: number, status: RoomItem["status"]) => {
    try {
      const response = await apiClient.patch(`/api/rooms/${roomId}/status?status=${status}`);
      const updatedRoom = response.data;
      setRooms((current) => current.map((room) => room.id === roomId ? { ...room, ...updatedRoom } : room));
      showToast(`Đã chuyển phòng sang ${status === "AVAILABLE" ? "Trống" : status === "BOOKED" ? "Đã đặt" : status === "MAINTENANCE" ? "Bảo trì" : "Đang có khách"}.`, "success");
      return true;
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể thay đổi trạng thái phòng."), "error");
      return false;
    }
  };

  const handleTransferRoom = async () => {
    if (!swapSourceRoom || !swapTargetRoomName) return;
    const targetRoom = rooms.find((room) => room.roomName === swapTargetRoomName);
    if (!targetRoom) return;

    setIsSwapLoading(true);
    try {
      await apiClient.patch(`/api/rooms/${swapSourceRoom.id}/transfer`, { targetRoomId: targetRoom.id });
      setIsSwapOpen(false);
      setSwapTargetRoomName("");
      showToast(`Đã chuyển khách từ phòng #${swapSourceRoom.roomName} sang #${targetRoom.roomName}.`, "success");
      await fetchData();
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể chuyển khách sang phòng mới."), "error");
    } finally {
      setIsSwapLoading(false);
    }
  };

  const openRoomCheckout = async (roomId: number) => {
    setCheckoutRoomId(roomId);
    try {
      const response = await apiClient.get(`/api/rooms/${roomId}/active-reservation`);
      const reservationId = Number(response.data?.reservationId);
      if (!Number.isFinite(reservationId) || reservationId <= 0) {
        throw new Error("Backend không trả về reservationId hợp lệ");
      }
      const detailResponse = await apiClient.get(`/api/reservations/${reservationId}`);
      const reservation = detailResponse.data?.data ?? detailResponse.data;
      const roomCount = (reservation.roomTypes || []).reduce(
        (total: number, roomType: { quantity?: number }) => total + Number(roomType.quantity || 0),
        0,
      );
      if (roomCount > 1) {
        setCheckoutConfirmation({
          reservationId,
          reservationCode: reservation.reservationCode || `#${reservationId}`,
          roomCount,
        });
      } else {
        router.push(`/dashboard/reservations?finalPaymentId=${reservationId}`);
      }
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể mở đối soát checkout của phòng này."), "error");
    } finally {
      setCheckoutRoomId(null);
    }
  };

  const handleCleaningStatusUpdate = async (roomId: number, cleaningStatus: RoomItem["cleaningStatus"]) => {
    try {
      const res = await apiClient.patch(`/api/rooms/${roomId}/cleaning-status?cleaningStatus=${cleaningStatus}`);
      const updatedRoom = res.data;
      setRooms(prev =>
        prev.map(r =>
          r.id === roomId
            ? {
                ...r,
                ...updatedRoom,
                roomTypeId: updatedRoom.roomTypeId ?? r.roomTypeId,
                roomTypeName: updatedRoom.roomTypeName ?? r.roomTypeName,
              }
            : r
        )
      );
      const cleaningLabel = {
        CLEAN: localize("Sạch", "Clean"),
        DIRTY: localize("Bẩn", "Dirty"),
        IN_PROGRESS: localize("Đang dọn", "Cleaning"),
      }[cleaningStatus];
      showToast(localize(
        `Đã chuyển tình trạng vệ sinh sang ${cleaningLabel}.`,
        `Housekeeping status changed to ${cleaningLabel}.`,
      ), "success");
      return true;
    } catch (error: unknown) {
      showToast(getApiErrorMessage(error, "Không thể cập nhật trạng thái vệ sinh phòng."), "error");
      return false;
    }
  };

  const handleOpenConfirmClean = (room: RoomItem, targetStatus: RoomItem["cleaningStatus"]) => {
    if (room.cleaningStatus === targetStatus) return;
    setCleanTargetRoom(room);
    setCleanTargetStatus(targetStatus);
    setConfirmCleanOpen(true);
  };

  const handleContextCleaningStatus = (targetStatus: RoomItem["cleaningStatus"]) => {
    if (!contextMenu) return;
    const room = rooms.find((item) => item.id === contextMenu.roomId);
    setContextMenu(null);
    if (room) handleOpenConfirmClean(room, targetStatus);
  };

  const handleConfirmCleanSubmit = async () => {
    if (!cleanTargetRoom) return;
    const updated = await handleCleaningStatusUpdate(cleanTargetRoom.id, cleanTargetStatus);
    if (updated) {
      setConfirmCleanOpen(false);
    }
  };

  const handleOpenMaintenanceForm = (room: RoomItem) => {
    setMaintenanceTargetRoom(room);
    setMaintenanceFormOpen(true);
  };

  const handleOpenMaintenanceDetail = async (room: RoomItem) => {
    setMaintenanceDetailRoom(room);
    setMaintenanceDetailOpen(true);
    try {
      const response = await apiClient.get(`/api/rooms/${room.id}`);
      const detail = response.data?.data ?? response.data;
      setMaintenanceDetailRoom({
        ...room,
        ...detail,
        maintenanceHistory: detail?.maintenanceHistory || [],
      });
    } catch (error: unknown) {
      showToast(
        getApiErrorMessage(error, "Không thể tải lịch sử bảo trì của phòng."),
        "error",
      );
    }
  };

  // Operational metrics combine physical status and housekeeping. A vacant
  // dirty room is intentionally not counted as ready for assignment.
  const roomSummary = useMemo(() => summarizeRoomOperations(rooms), [rooms]);
  const activeRoomCount = roomSummary.reserved + roomSummary.occupied;

  const getRoomStatusLabel = (status: RoomItem["status"] | "All") => ({
    All: localize("Tất cả", "All"),
    AVAILABLE: localize("Trống", "Vacant"),
    BOOKED: localize("Đã đặt", "Booked"),
    CHECKED_IN: localize("Đang có khách", "Occupied"),
    MAINTENANCE: localize("Bảo trì", "Maintenance"),
  }[status]);

  const getCleaningStatusLabel = (status: RoomItem["cleaningStatus"]) => ({
    CLEAN: localize("Sạch", "Clean"),
    DIRTY: localize("Bẩn", "Dirty"),
    IN_PROGRESS: localize("Đang dọn", "Cleaning"),
  }[status]);

  const getOperationalStateLabel = (state: RoomOperationalState) => ({
    READY: localize("Sẵn sàng", "Ready"),
    NEEDS_CLEANING: localize("Cần dọn", "Needs cleaning"),
    CLEANING: localize("Đang dọn", "Cleaning"),
    RESERVED: localize("Đã đặt", "Reserved"),
    OCCUPIED: localize("Đang có khách", "Occupied"),
    MAINTENANCE: localize("Bảo trì", "Maintenance"),
  }[state]);

  const getOperationalStateDescription = (state: RoomOperationalState) => ({
    READY: localize("Phòng trống, sạch và có thể gán cho khách.", "Vacant, clean and ready for assignment."),
    NEEDS_CLEANING: localize("Phòng đang trống nhưng phải dọn trước khi gán.", "Vacant but must be cleaned before assignment."),
    CLEANING: localize("Buồng phòng đang xử lý vệ sinh.", "Housekeeping is cleaning this room."),
    RESERVED: localize("Trạng thái đã đặt từ dữ liệu tương thích cũ.", "Reserved state from legacy-compatible data."),
    OCCUPIED: localize("Phòng đang được sử dụng trong một đơn lưu trú.", "The room is assigned to an active stay."),
    MAINTENANCE: localize("Phòng ngừng bán trong thời gian bảo trì.", "The room is out of service for maintenance."),
  }[state]);

  const hasActiveRoomFilters = Boolean(
    searchQuery
    || selectedStatus !== "All"
    || selectedType !== "All"
    || !cleaningFilter.CLEAN
    || !cleaningFilter.DIRTY
    || !cleaningFilter.IN_PROGRESS,
  );

  const resetRoomFilters = () => {
    setSearchQuery("");
    setSelectedStatus("All");
    setSelectedType("All");
    setCleaningFilter({ CLEAN: true, DIRTY: true, IN_PROGRESS: true });
  };

  return (
    <div className="ops-page mx-auto w-full max-w-[1440px] space-y-7 p-4 sm:p-6 lg:p-8">
      {/* Header */}
      <div className="flex flex-col gap-4 border-b border-[#0F2A43]/10 pb-6 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <div className="flex items-center gap-3"><p className="text-xs font-bold uppercase tracking-[0.2em] text-[#80632F]">{localize("Vận hành buồng phòng", "Room operations")}</p>
          <span className="px-2 py-0.5 bg-emerald-50 text-emerald-700 border border-emerald-250 rounded-md text-[10px] font-bold flex items-center gap-1 shrink-0">
            <span className="w-1.5 h-1.5 bg-emerald-500 rounded-full" />
            {localize("Dữ liệu trực tiếp", "Live data")}
          </span>
          </div><h1 className="mt-2 font-serif text-3xl font-bold tracking-tight text-[#0F2A43] md:text-4xl">{localize("Quản lý phòng", "Room management")}</h1><p className="mt-2 text-sm font-medium text-[#66727C]">{localize("Theo dõi trạng thái phòng, vệ sinh và bảo trì theo từng tầng.", "Track room status, housekeeping and maintenance by floor.")}</p>
        </div>
        {isAdmin && (
          <button
            onClick={openCreateModal}
            className="self-start sm:self-auto px-5 py-2.5 bg-[#0F2A43] hover:bg-[#091E30] text-white font-semibold text-sm rounded-lg shadow-sm transition-all duration-300 flex items-center gap-2"
          >
            <span>+</span> {localize("Thêm phòng", "Add room")}
          </button>
        )}
      </div>

      {/* Metrics Cards Grid */}
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4 lg:gap-4">
        <div className="rounded-2xl border border-[#0F2A43]/10 bg-white p-4 shadow-sm sm:p-5">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-[#F0EADF] text-[#B8944F] rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <rect x="3" y="3" width="18" height="18" rx="2" />
                <path d="M9 17v-5h6v5" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{roomSummary.total}</span>
              <span className="mt-1 block text-[11px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Tổng số phòng", "Total rooms")}</span>
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-emerald-200 bg-emerald-50/35 p-4 shadow-sm sm:p-5">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-emerald-50 text-emerald-600 rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{roomSummary.ready}</span>
              <span className="mt-1 block text-[11px] font-bold uppercase tracking-wider text-emerald-800">{localize("Phòng sẵn sàng", "Available rooms")}</span>
              <span className="mt-1 block text-[10px] font-medium text-emerald-700/80">{localize("Trống + sạch", "Vacant + clean")}</span>
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-blue-200 bg-blue-50/35 p-4 shadow-sm sm:p-5">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-blue-50 text-blue-600 rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="9" cy="7" r="4" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{activeRoomCount}</span>
              <span className="mt-1 block text-[11px] font-bold uppercase tracking-wider text-blue-800">{localize("Đã đặt / đang ở", "Booked / occupied")}</span>
              <span className="mt-1 block text-[10px] font-medium text-blue-700/80">
                {localize(`${roomSummary.occupied} đang ở · ${roomSummary.reserved} đã đặt`, `${roomSummary.occupied} occupied · ${roomSummary.reserved} reserved`)}
              </span>
            </div>
          </div>
        </div>

        <div className="rounded-2xl border border-rose-200 bg-rose-50/35 p-4 shadow-sm sm:p-5">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-red-50 text-red-600 rounded-lg">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" className="w-5 h-5">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                <line x1="12" y1="9" x2="12" y2="13" />
                <line x1="12" y1="17" x2="12.01" y2="17" />
              </svg>
            </div>
            <div>
              <span className="block text-2xl font-serif font-bold text-[#0F2A43]">{roomSummary.attention}</span>
              <span className="mt-1 block text-[11px] font-bold uppercase tracking-wider text-rose-800">{localize("Cần xử lý", "Needs attention")}</span>
              <span className="mt-1 block text-[10px] font-medium text-rose-700/80">
                {localize(`${roomSummary.needsCleaning} cần dọn · ${roomSummary.cleaning} đang dọn · ${roomSummary.maintenance} bảo trì`, `${roomSummary.needsCleaning} dirty · ${roomSummary.cleaning} cleaning · ${roomSummary.maintenance} maintenance`)}
              </span>
            </div>
          </div>
        </div>
      </div>

      <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white shadow-sm" aria-labelledby="confirmed-reservations-title">
        <header className="flex flex-col gap-3 border-b border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <div className="flex items-center gap-2">
              <span className="h-2.5 w-2.5 rounded-full bg-blue-500" aria-hidden="true" />
              <h2 id="confirmed-reservations-title" className="text-base font-bold text-[#0F2A43]">{localize("Đơn đã xác nhận · Chờ nhận phòng", "Confirmed · Awaiting check-in")}</h2>
            </div>
            <p className="mt-1 text-xs text-[#66727C]">{localize("Nhu cầu phòng sắp tới theo loại phòng và thời gian lưu trú dự kiến.", "Upcoming room demand by room type and planned stay period.")}</p>
          </div>
          <span className="inline-flex min-h-8 items-center self-start rounded-lg border border-blue-200 bg-blue-50 px-3 text-xs font-bold tabular-nums text-blue-700 sm:self-auto">
            {confirmedReservations.length} {localize("đơn", "reservations")}
          </span>
        </header>

        {confirmedReservations.length === 0 ? (
          <div className="px-5 py-8 text-center text-sm font-medium text-[#66727C]">{localize("Hiện không có đơn đã xác nhận đang chờ nhận phòng.", "There are no confirmed reservations awaiting check-in.")}</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[900px] text-left text-sm">
              <thead className="bg-white text-[11px] font-bold uppercase tracking-[0.08em] text-[#66727C]">
                <tr>
                  <th className="px-5 py-3">{localize("Đơn / khách", "Reservation / guest")}</th>
                  <th className="px-5 py-3">{localize("Loại phòng & số lượng", "Room type & quantity")}</th>
                  <th className="px-5 py-3">{localize("Nhận phòng", "Check-in")}</th>
                  <th className="px-5 py-3">{localize("Trả phòng", "Check-out")}</th>
                  <th className="px-5 py-3 text-right">{localize("Thao tác", "Actions")}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#0F2A43]/8">
                {confirmedReservations.map((reservation) => (
                  <tr key={reservation.id} className="transition-colors hover:bg-[#FBFAF6]">
                    <td className="px-5 py-4">
                      <p className="font-bold text-[#0F2A43]">{reservation.reservationCode}</p>
                      <p className="mt-1 text-xs text-[#66727C]">{reservation.customerName || localize("Khách đặt phòng", "Booking guest")}{reservation.guestCount ? ` · ${reservation.guestCount} ${localize("khách", "guests")}` : ""}</p>
                    </td>
                    <td className="px-5 py-4">
                      <div className="flex max-w-md flex-wrap gap-2">
                        {(reservation.roomTypes || []).map((roomType) => (
                          <span key={roomType.id} className="inline-flex min-h-8 items-center rounded-lg border border-[#B8944F]/35 bg-[#F0EADF] px-3 text-xs font-bold text-[#80632F]">
                            {localize(roomType.roomTypeName, roomType.roomTypeNameEn)} × {roomType.quantity}
                          </span>
                        ))}
                        {!reservation.roomTypes?.length && <span className="text-xs text-[#66727C]">{localize("Chưa có chi tiết loại phòng", "Room type details unavailable")}</span>}
                      </div>
                    </td>
                    <td className="whitespace-nowrap px-5 py-4 font-semibold tabular-nums text-[#0F2A43]">{formatReservationTime(reservation.checkIn, localeTag)}</td>
                    <td className="whitespace-nowrap px-5 py-4 font-semibold tabular-nums text-[#0F2A43]">{formatReservationTime(reservation.checkOut, localeTag)}</td>
                    <td className="px-5 py-4 text-right">
                      <button
                        type="button"
                        onClick={() => router.push(`/dashboard/reservations?reservationId=${reservation.id}`)}
                        className="min-h-10 rounded-lg border border-[#0F2A43]/20 bg-white px-4 text-xs font-bold text-[#0F2A43] hover:border-[#0F2A43] hover:bg-[#0F2A43] hover:text-white"
                      >
                        {localize("Xem và nhận phòng", "View and check in")}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {/* Filter and Cards Section */}
      <div className="space-y-6">
        <DashboardFilterPanel
          title={localize("Tìm phòng trên sơ đồ", "Find rooms")}
          description={localize("Lọc theo trạng thái phòng, loại phòng và tiến độ vệ sinh", "Filter by room status, room type and housekeeping progress")}
          resultCount={filteredRooms.length}
          resultLabel={localize("phòng phù hợp", "matching rooms")}
          resultNote={localize(`${roomsByFloor.length} tầng đang hiển thị`, `${roomsByFloor.length} floors shown`)}
          hasActiveFilters={hasActiveRoomFilters}
          activeFilterCount={Number(Boolean(searchQuery)) + Number(selectedStatus !== "All") + Number(selectedType !== "All") + Number(!cleaningFilter.CLEAN || !cleaningFilter.DIRTY || !cleaningFilter.IN_PROGRESS)}
          activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
          onReset={resetRoomFilters}
          resetLabel={localize("Xóa toàn bộ bộ lọc", "Clear all filters")}
          actions={(
            <>
              <FilterQuickButton active={viewMode === "floor"} onClick={() => setViewMode("floor")}>
                {localize("Sơ đồ tầng", "Floor plan")}
              </FilterQuickButton>
              <FilterQuickButton active={viewMode === "list"} onClick={() => setViewMode("list")}>
                {localize("Danh sách", "List")}
              </FilterQuickButton>
            </>
          )}
        >
          <DashboardSearchField
            id="room-search"
            label={localize("Tìm kiếm", "Search")}
            value={searchQuery}
            onChange={setSearchQuery}
            placeholder={localize("Số phòng, tên phòng hoặc loại phòng...", "Room number, name or room type...")}
            clearLabel={localize("Xóa từ khóa", "Clear search")}
          />
          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            <DashboardSelectField
              id="room-status"
               label={localize("Tình trạng sử dụng", "Occupancy state")}
              value={selectedStatus}
              onChange={(event) => setSelectedStatus(event.target.value as typeof selectedStatus)}
            >
              {(["All", "AVAILABLE", "BOOKED", "CHECKED_IN", "MAINTENANCE"] as const).map((status) => (
                <option key={status} value={status}>{getRoomStatusLabel(status)}</option>
              ))}
            </DashboardSelectField>
            <DashboardSelectField
              id="room-type"
              label={localize("Loại phòng", "Room type")}
              value={selectedType}
              onChange={(event) => setSelectedType(event.target.value)}
            >
              <option value="All">{localize("Tất cả loại phòng", "All room types")}</option>
              {roomTypes.map((type) => (
                <option key={type.id} value={String(type.id)}>{localize(type.typeName, type.typeNameEn)}</option>
              ))}
            </DashboardSelectField>
            <fieldset className="min-w-0">
              <legend className="mb-2 text-xs font-bold text-[#66727C]">{localize("Tình trạng vệ sinh", "Housekeeping")}</legend>
              <div className="grid gap-2 sm:grid-cols-3 xl:grid-cols-1 2xl:grid-cols-3">
                {(["CLEAN", "DIRTY", "IN_PROGRESS"] as const).map((status) => (
                  <label key={status} className={`flex min-h-11 cursor-pointer items-center gap-2 rounded-lg border px-3 text-xs font-bold transition ${
                    cleaningFilter[status]
                      ? status === "CLEAN"
                        ? "border-emerald-200 bg-emerald-50 text-emerald-800"
                        : status === "DIRTY"
                          ? "border-orange-200 bg-orange-50 text-orange-800"
                          : "border-violet-200 bg-violet-50 text-violet-800"
                      : "border-[#0F2A43]/12 bg-white text-[#66727C] opacity-65 hover:border-[#0F2A43]/30"
                  }`}>
                    <input
                      type="checkbox"
                      checked={cleaningFilter[status]}
                      onChange={(event) => setCleaningFilter({ ...cleaningFilter, [status]: event.target.checked })}
                      className="h-4 w-4 rounded border-[#0F2A43]/25 text-[#0F2A43] focus:ring-[#B8944F]"
                    />
                    <span>{getCleaningStatusLabel(status)}</span>
                  </label>
                ))}
              </div>
            </fieldset>
          </div>
        </DashboardFilterPanel>

        <div className="flex flex-wrap gap-x-5 gap-y-2 rounded-xl border border-[#0F2A43]/10 bg-[#E5E9ED] px-4 py-3 text-xs font-semibold text-[#66727C]">
          <span className="font-bold text-[#0F2A43]">{localize("Trạng thái vận hành:", "Operational status:")}</span>
          {(["READY", "NEEDS_CLEANING", "CLEANING", "RESERVED", "OCCUPIED", "MAINTENANCE"] as const).map((state) => (
            <span key={state} className="flex items-center gap-2">
              <i className={`h-2.5 w-2.5 rounded-full ${ROOM_OPERATIONAL_STYLES[state].dot}`} aria-hidden="true" />
              {getOperationalStateLabel(state)}
            </span>
          ))}
          <span className="ml-auto hidden text-[11px] font-medium text-[#66727C] lg:inline">{localize("Nhấp chuột phải vào ô phòng để mở thao tác nhanh", "Right-click a room tile for quick actions")}</span>
        </div>

        {isLoading ? (
          <div className="space-y-6" role="status" aria-label={localize("Đang tải dữ liệu phòng", "Loading room data")}>
            {[0, 1].map((floor) => <section key={floor} className="rounded-2xl border border-[#0F2A43]/10 bg-white p-4 sm:p-6"><div className="h-7 w-32 animate-pulse rounded bg-[#E5E9ED]" /><div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">{[0, 1, 2, 3, 4, 5].map((room) => <div key={room} className="h-32 animate-pulse rounded-xl bg-[#F1F0EA]" />)}</div></section>)}
          </div>
        ) : filteredRooms.length === 0 ? (
          <div className="rounded-xl border-2 border-dashed border-[#0F2A43]/10 bg-white px-5 py-12 text-center text-sm font-semibold text-[#66727C]">
            <p>{localize("Không có phòng phù hợp với bộ lọc hiện tại.", "No rooms match the current filters.")}</p>
            {hasActiveRoomFilters && (
              <button type="button" onClick={resetRoomFilters} className="mt-4 min-h-10 rounded-lg border border-[#0F2A43]/20 bg-[#F1F0EA] px-4 text-xs font-bold text-[#0F2A43] transition hover:border-[#B8944F] hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]">
                {localize("Xóa bộ lọc", "Clear filters")}
              </button>
            )}
          </div>
        ) : viewMode === "floor" ? (
          /* FLOOR MATRIX GRID VIEW */
          <div className="space-y-8">
            {roomsByFloor.map(({ floorNum, rooms }) => (
              <section key={floorNum} className="space-y-5 rounded-2xl border border-[#0F2A43]/10 bg-white p-4 shadow-sm sm:p-6">
                <div className="pb-2 border-b border-gray-100 flex items-center justify-between">
                  <div><p className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#80632F]">{localize("Sơ đồ tầng", "Floor plan")}</p><h3 className="mt-1 font-serif text-xl font-bold text-[#0F2A43]">{localize("Tầng", "Floor")} {floorNum}</h3></div>
                  <span className="rounded-lg bg-[#E5E9ED] px-3 py-1.5 text-xs font-bold text-[#66727C]">{rooms.length} {localize("phòng", "rooms")}</span>
                </div>
                
                <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6">
                  {rooms.map((room) => {
                    const operationalState = getRoomOperationalState(room);
                    const operationalStyle = ROOM_OPERATIONAL_STYLES[operationalState];
                    const operationalLabel = getOperationalStateLabel(operationalState);
                    const tooltipText = `${localize("Phòng", "Room")} #${room.roomName} · ${localize(room.roomTypeName, room.roomTypeNameEn)} · ${operationalLabel}: ${getOperationalStateDescription(operationalState)}`;

                    // Cleaning indicator
                    let cleaningIconSvg = (
                      <svg className="w-2.5 h-2.5 text-emerald-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                        <polyline points="20 6 9 17 4 12" />
                      </svg>
                    );
                    let cleaningText = getCleaningStatusLabel("CLEAN");
                    let cleaningColor = "text-emerald-600";
                    if (room.cleaningStatus === "DIRTY") {
                      cleaningIconSvg = (
                        <svg className="w-2.5 h-2.5 text-orange-600" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                          <line x1="18" y1="6" x2="6" y2="18" />
                          <line x1="6" y1="6" x2="18" y2="18" />
                        </svg>
                      );
                      cleaningText = getCleaningStatusLabel("DIRTY");
                      cleaningColor = "text-orange-700";
                    } else if (room.cleaningStatus === "IN_PROGRESS") {
                      cleaningIconSvg = (
                        <svg className="w-2.5 h-2.5 text-violet-600 animate-spin" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                          <line x1="12" y1="2" x2="12" y2="6" />
                          <line x1="12" y1="18" x2="12" y2="22" />
                          <line x1="4.93" y1="4.93" x2="7.76" y2="7.76" />
                          <line x1="16.24" y1="16.24" x2="19.07" y2="19.07" />
                          <line x1="2" y1="12" x2="6" y2="12" />
                          <line x1="18" y1="12" x2="22" y2="12" />
                          <line x1="6.34" y1="17.66" x2="8.46" y2="15.54" />
                          <line x1="15.54" y1="13.46" x2="17.66" y2="11.34" />
                        </svg>
                      );
                      cleaningText = getCleaningStatusLabel("IN_PROGRESS");
                      cleaningColor = "text-violet-700";
                    }

                    return (
                      <div
                        key={room.id}
                        title={tooltipText}
                        onContextMenu={(e) => {
                          e.preventDefault();
                          setContextMenu({
                            roomId: room.id,
                            roomName: room.roomName,
                            status: room.status,
                            cleaningStatus: room.cleaningStatus || "CLEAN",
                            x: e.clientX,
                            y: e.clientY
                          });
                        }}
                        data-operational-state={operationalState}
                        className={`relative flex min-h-40 cursor-pointer flex-col items-center justify-center rounded-xl border p-4 text-center shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:shadow-md ${operationalStyle.tile}`}
                      >
                        {isAdmin && (
                          <button
                            type="button"
                            disabled={room.status === "CHECKED_IN"}
                            onClick={(event) => {
                              event.stopPropagation();
                              openEditModal(room);
                            }}
                            aria-label={localize(`Sửa thông tin phòng ${room.roomName}`, `Edit room ${room.roomName}`)}
                            title={localize("Sửa thông tin phòng", "Edit room")}
                            className="absolute right-2 top-2 inline-flex h-9 w-9 items-center justify-center rounded-lg border border-[#0F2A43]/10 bg-white/90 text-[#0F2A43] shadow-sm transition hover:border-[#B8944F] hover:bg-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-35"
                          >
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="h-4 w-4" aria-hidden="true">
                              <path d="M12 20h9" />
                              <path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L8 18l-4 1 1-4Z" />
                            </svg>
                          </button>
                        )}
                        <span className="max-w-full truncate text-[11px] font-bold uppercase tracking-wider opacity-70">{localize(room.roomTypeName, room.roomTypeNameEn)}</span>
                        <span className="mt-1 font-serif text-2xl font-bold">#{room.roomName}</span>
                        <span className={`mt-2 inline-flex min-h-7 items-center gap-1.5 rounded-full border px-2.5 text-[10px] font-extrabold ${operationalStyle.badge}`}>
                          <i className={`h-1.5 w-1.5 rounded-full ${operationalStyle.dot}`} aria-hidden="true" />
                          {operationalLabel}
                        </span>
                        
                        <div className="mt-2.5 flex flex-col items-center gap-1">
                          {room.status === "CHECKED_IN" ? (
                            <button
                              type="button"
                              disabled={checkoutRoomId === room.id}
                              onClick={(event) => {
                                event.stopPropagation();
                                void openRoomCheckout(room.id);
                              }}
                              className="inline-flex min-h-9 items-center rounded-lg border border-rose-200 bg-rose-50 px-3 text-[10px] font-extrabold text-rose-700 hover:bg-rose-100 disabled:cursor-wait disabled:opacity-60"
                            >
                              {checkoutRoomId === room.id ? localize("Đang mở...", "Opening...") : localize("Trả phòng", "Check out")}
                            </button>
                          ) : room.status === "MAINTENANCE" ? null : (
                            <select
                              value={room.status}
                              onClick={(event) => event.stopPropagation()}
                              onChange={(event) => {
                                const nextStatus = event.target.value as RoomItem["status"];
                                if (nextStatus === "MAINTENANCE") {
                                  handleOpenMaintenanceForm(room);
                                  return;
                                }
                                void handleStatusUpdate(room.id, nextStatus);
                              }}
                              aria-label={localize(`Trạng thái phòng ${room.roomName}`, `Room ${room.roomName} status`)}
                              title={localize("Đổi trạng thái phòng", "Change room status")}
                              className={`min-h-8 rounded-md border px-2 text-[10px] font-extrabold outline-none focus:ring-2 focus:ring-[#B8944F] ${operationalStyle.control}`}
                            >
                              <option value="AVAILABLE">{getRoomStatusLabel("AVAILABLE")}</option>
                              {room.status === "BOOKED" && <option value="BOOKED" disabled>{localize("Đã đặt (dữ liệu cũ)", "Booked (legacy data)")}</option>}
                              <option value="MAINTENANCE">{getRoomStatusLabel("MAINTENANCE")}</option>
                            </select>
                          )}
                          <button
                            type="button"
                            onClick={(e) => {
                              e.stopPropagation();
                              handleOpenConfirmClean(room, room.cleaningStatus === "CLEAN" ? "DIRTY" : "CLEAN");
                            }}
                            aria-label={localize(`Cập nhật vệ sinh phòng ${room.roomName}, hiện tại ${cleaningText}`, `Update housekeeping for room ${room.roomName}, currently ${cleaningText}`)}
                            className={`mt-0.5 flex min-h-8 items-center gap-1.5 rounded-md px-2 text-[9px] font-bold ${cleaningColor} transition hover:bg-white/70 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]`}
                          >
                            {cleaningIconSvg}
                            <span>{cleaningText}</span>
                          </button>
                          
                          {room.status === "MAINTENANCE" && (
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                handleOpenMaintenanceDetail(room);
                              }}
                              className="mt-1.5 rounded-md border border-red-200 bg-red-100 px-2 py-1 text-[10px] font-extrabold text-red-750 transition-colors hover:bg-red-200"
                            >
                              {localize("Chi tiết bảo trì", "Maintenance details")}
                            </button>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              </section>
            ))}
          </div>
        ) : (
          /* LIST CARDS VIEW */
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
            {filteredRooms.map((room) => {
              const operationalState = getRoomOperationalState(room);
              const operationalStyle = ROOM_OPERATIONAL_STYLES[operationalState];
              return (
              <article key={room.id} data-operational-state={operationalState} className={`flex flex-col justify-between rounded-2xl border p-5 shadow-sm transition-all duration-200 hover:-translate-y-0.5 hover:shadow-lg ${operationalStyle.card}`}>
                <div>
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <span className="block text-[11px] font-bold uppercase tracking-wider text-[#66727C]">{localize("Tầng", "Floor")} {room.floor}</span>
                      <h4 className="mt-0.5 font-serif text-2xl font-bold text-[#0F2A43]">{localize("Phòng", "Room")} #{room.roomName}</h4>
                    </div>

                    <span className={`inline-flex min-h-7 shrink-0 items-center gap-1.5 rounded-full border px-2.5 text-[10px] font-bold ${operationalStyle.badge}`}>
                      <span className={`h-1.5 w-1.5 rounded-full ${operationalStyle.dot}`} />
                      {getOperationalStateLabel(operationalState)}
                    </span>
                  </div>

                  <div className="mt-4 space-y-2 text-xs font-medium text-[#66727C]">
                    <div className="flex justify-between">
                      <span>{localize("Loại phòng", "Room type")}</span>
                      <span className="text-[#0F2A43] font-bold">{localize(room.roomTypeName, room.roomTypeNameEn)}</span>
                    </div>
                    <div className="flex justify-between">
                      <span>{localize("Giá qua đêm", "Overnight rate")}</span>
                      <span className="font-bold text-[#80632F]">
                        {roomTypeById.get(room.roomTypeId)?.overnightPrice != null
                          ? `${Number(roomTypeById.get(room.roomTypeId)?.overnightPrice).toLocaleString(localeTag)} đ`
                          : "—"}
                      </span>
                    </div>
                    <div className="flex justify-between pt-1 border-t border-gray-100">
                      <span>{localize("Tình trạng vệ sinh", "Housekeeping status")}</span>
                      <span className={`font-bold ${
                        room.cleaningStatus === "DIRTY" ? "text-orange-700" :
                        room.cleaningStatus === "IN_PROGRESS" ? "text-violet-700" : "text-emerald-600"
                      }`}>
                        {getCleaningStatusLabel(room.cleaningStatus || "CLEAN")}
                      </span>
                    </div>
                    {room.description && (
                      <p className="mt-3 pt-3 border-t border-gray-100 text-gray-400 font-light italic">
                        &quot;{room.description}&quot;
                      </p>
                    )}
                  </div>
                </div>

                {/* Footer Controls */}
                <div className="mt-6 flex flex-col gap-3 border-t border-[#0F2A43]/5 pt-4 sm:flex-row sm:items-center sm:justify-between">
                  <div className="flex min-w-0 flex-wrap gap-2">
                    {room.status === "CHECKED_IN" ? (
                      <button type="button" onClick={() => void openRoomCheckout(room.id)} className="min-h-9 rounded-lg border border-rose-200 bg-rose-50 px-3 text-[10px] font-bold text-rose-700">{localize("Trả phòng", "Check out")}</button>
                    ) : room.status === "MAINTENANCE" ? (
                      <button type="button" onClick={() => handleOpenMaintenanceDetail(room)} className="min-h-9 rounded-lg border border-rose-200 bg-rose-50 px-3 text-[10px] font-bold text-rose-700">{localize("Chi tiết bảo trì", "Maintenance details")}</button>
                    ) : (
                      <select
                        value={room.status}
                        onChange={(event) => {
                          const nextStatus = event.target.value as RoomItem["status"];
                          if (nextStatus === "MAINTENANCE") handleOpenMaintenanceForm(room);
                          else void handleStatusUpdate(room.id, nextStatus);
                        }}
                        aria-label={localize(`Trạng thái phòng ${room.roomName}`, `Room ${room.roomName} status`)}
                        className={`min-h-10 rounded-lg border px-2.5 text-[10px] font-bold focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] ${operationalStyle.control}`}
                      >
                        <option value="AVAILABLE">{getRoomStatusLabel("AVAILABLE")}</option>
                        {room.status === "BOOKED" && <option value="BOOKED" disabled>{localize("Đã đặt (cũ)", "Booked (legacy)")}</option>}
                        <option value="MAINTENANCE">{getRoomStatusLabel("MAINTENANCE")}</option>
                      </select>
                    )}

                    <select
                      value={room.cleaningStatus || "CLEAN"}
                      onChange={(event) => handleOpenConfirmClean(room, event.target.value as RoomItem["cleaningStatus"])}
                      aria-label={localize(`Tình trạng vệ sinh phòng ${room.roomName}`, `Housekeeping status for room ${room.roomName}`)}
                      className="min-h-10 rounded-lg border border-[#0F2A43]/15 bg-white/80 px-2.5 text-[10px] font-bold text-[#66727C] focus:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F]"
                    >
                      <option value="CLEAN">{getCleaningStatusLabel("CLEAN")}</option>
                      <option value="DIRTY">{getCleaningStatusLabel("DIRTY")}</option>
                      <option value="IN_PROGRESS">{getCleaningStatusLabel("IN_PROGRESS")}</option>
                    </select>
                  </div>

                  {isAdmin && (
                    <div className="flex flex-wrap gap-2 sm:justify-end">
                      <button
                        disabled={room.status === "CHECKED_IN"}
                        onClick={() => openEditModal(room)}
                        className="min-h-10 rounded-lg border border-gray-200 px-3 text-[10px] font-bold text-gray-500 transition-colors hover:border-[#B8944F] hover:text-[#B8944F] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#B8944F] disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {localize("Sửa", "Edit")}
                      </button>
                      <button
                        onClick={() => openDeleteModal(room)}
                        className="min-h-10 rounded-lg border border-red-200 px-3 text-[10px] font-bold text-red-600 transition-colors hover:bg-red-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-300"
                      >
                        {localize("Xóa", "Delete")}
                      </button>
                    </div>
                  )}
                </div>
              </article>
              );
            })}
          </div>
        )}
      </div>

      {/* CREATE MODAL */}
      {isAdmin && (
        <ViewportModal
          open={isCreateOpen}
          onClose={() => setIsCreateOpen(false)}
          labelledBy="create-room-title"
          describedBy="create-room-description"
          busy={roomMutation === "create"}
          panelClassName="max-w-lg"
        >
          <form onSubmit={handleCreateSubmit} className="flex min-h-0 flex-col">
            <header className="border-b border-[#0F2A43]/10 bg-[#F7F4EC] px-5 py-4 sm:px-6">
              <h3 id="create-room-title" className="text-xl font-bold text-[#0F2A43]">{localize("Thêm phòng vật lý", "Add physical room")}</h3>
              <p id="create-room-description" className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Tên phòng có thể được đề xuất tự động theo tầng; bạn vẫn có thể chỉnh lại trước khi lưu.", "The room name can be suggested from its floor and edited before saving.")}</p>
            </header>
            <div className="min-h-0 space-y-4 overflow-y-auto px-5 py-5 sm:px-6">
              {roomFormError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-semibold text-rose-700">{roomFormError}</p>}
              <div>
                <div className="mb-1.5 flex items-center justify-between gap-3"><label htmlFor="create-room-name" className="block text-xs font-bold text-[#0F2A43]">{localize("Tên / số phòng", "Room name / number")} *</label><button type="button" onClick={() => { setFormData((current) => ({ ...current, roomName: suggestRoomName(current.floor) })); setRoomNameAutoFilled(true); setRoomFormError(""); }} className="min-h-11 px-2 text-xs font-bold text-[#80632F] underline-offset-4 hover:underline">{localize("Tự điền", "Auto-fill")}</button></div>
                <input
                  id="create-room-name"
                  data-modal-autofocus
                  type="text"
                  required
                  value={formData.roomName}
                  onChange={(e) => { setRoomNameAutoFilled(false); setFormData({ ...formData, roomName: e.target.value }); setRoomFormError(""); }}
                  className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                  placeholder={localize("Ví dụ: Phòng 304", "e.g. Room 304")}
                />
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor="create-room-type" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">{localize("Loại phòng", "Room type")} *</label>
                  <select
                    id="create-room-type"
                    value={formData.roomTypeId}
                    onChange={(e) => { setFormData({ ...formData, roomTypeId: Number(e.target.value) }); setRoomFormError(""); }}
                    className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                  >
                    {roomTypes.map((t) => (
                      <option key={t.id} value={t.id}>
                        {localize(t.typeName, t.typeNameEn)} ({t.overnightPrice != null
                          ? `${Number(t.overnightPrice).toLocaleString(localeTag)} đ/${localize("đêm", "night")}`
                          : localize("chưa có bảng giá", "rate unavailable")})
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label htmlFor="create-room-floor" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">{localize("Tầng", "Floor")} *</label>
                  <input
                    id="create-room-floor"
                    type="number"
                    min="1"
                    required
                    value={formData.floor}
                    onChange={(e) => {
                      const floor = Number(e.target.value);
                      setFormData((current) => ({ ...current, floor, roomName: roomNameAutoFilled ? suggestRoomName(floor) : current.roomName }));
                      setRoomFormError("");
                    }}
                    className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                  />
                </div>
              </div>

              <div>
                <label htmlFor="create-room-description-field" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">{localize("Mô tả", "Description")}</label>
                <textarea
                  id="create-room-description-field"
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  className="h-24 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3.5 py-2.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                  placeholder={localize("Đặc điểm bổ sung của phòng...", "Additional room features...")}
                />
              </div>
            </div>

            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
              <Button variant="secondary" disabled={roomMutation === "create"} onClick={() => setIsCreateOpen(false)}>{localize("Hủy", "Cancel")}</Button>
              <Button type="submit" loading={roomMutation === "create"} loadingLabel={localize("Đang tạo...", "Creating...")}>{localize("Tạo phòng", "Create room")}</Button>
            </footer>
          </form>
        </ViewportModal>
      )}

      {/* EDIT MODAL */}
      {isAdmin && (
        <ViewportModal
          open={isEditOpen}
          onClose={() => setIsEditOpen(false)}
          labelledBy="edit-room-title"
          describedBy="edit-room-description"
          busy={roomMutation === "edit"}
          panelClassName="max-w-lg"
        >
          <form onSubmit={handleEditSubmit} className="flex min-h-0 flex-col">
            <header className="border-b border-[#0F2A43]/10 bg-[#F7F4EC] px-5 py-4 sm:px-6">
              <h3 id="edit-room-title" className="text-xl font-bold text-[#0F2A43]">{localize("Chỉnh sửa phòng", "Edit room")}</h3>
              <p id="edit-room-description" className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Thay đổi thông tin phòng vật lý mà không làm thay đổi lịch sử đặt phòng.", "Update physical-room details without changing reservation history.")}</p>
            </header>
            <div className="min-h-0 space-y-4 overflow-y-auto px-5 py-5 sm:px-6">
              {roomFormError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-semibold text-rose-700">{roomFormError}</p>}
              <div>
                <label htmlFor="edit-room-name" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">{localize("Tên / số phòng", "Room name / number")} *</label>
                <input
                  id="edit-room-name"
                  data-modal-autofocus
                  type="text"
                  required
                  value={formData.roomName}
                  onChange={(e) => { setFormData({ ...formData, roomName: e.target.value }); setRoomFormError(""); }}
                  className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                />
              </div>

              <div className="grid gap-4 sm:grid-cols-2">
                <div>
                  <label htmlFor="edit-room-type" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">{localize("Loại phòng", "Room type")} *</label>
                  <select
                    id="edit-room-type"
                    value={formData.roomTypeId}
                    onChange={(e) => { setFormData({ ...formData, roomTypeId: Number(e.target.value) }); setRoomFormError(""); }}
                    className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                  >
                    {roomTypes.map((t) => (
                      <option key={t.id} value={t.id}>
                        {localize(t.typeName, t.typeNameEn)} ({t.overnightPrice != null
                          ? `${Number(t.overnightPrice).toLocaleString(localeTag)} đ/${localize("đêm", "night")}`
                          : localize("chưa có bảng giá", "rate unavailable")})
                      </option>
                    ))}
                  </select>
                </div>

                <div>
                  <label htmlFor="edit-room-floor" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">{localize("Tầng", "Floor")} *</label>
                  <input
                    id="edit-room-floor"
                    type="number"
                    min="1"
                    required
                    value={formData.floor}
                    onChange={(e) => { setFormData({ ...formData, floor: Number(e.target.value) }); setRoomFormError(""); }}
                    className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 px-3.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                  />
                </div>
              </div>

              <div>
                <label htmlFor="edit-room-description-field" className="mb-1.5 block text-xs font-bold text-[#0F2A43]">{localize("Mô tả", "Description")}</label>
                <textarea
                  id="edit-room-description-field"
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  className="h-24 w-full resize-none rounded-lg border border-[#0F2A43]/15 px-3.5 py-2.5 text-sm outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
                />
              </div>
            </div>

            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
              <Button variant="secondary" disabled={roomMutation === "edit"} onClick={() => setIsEditOpen(false)}>{localize("Hủy", "Cancel")}</Button>
              <Button type="submit" loading={roomMutation === "edit"} loadingLabel={localize("Đang lưu...", "Saving...")}>{localize("Lưu thay đổi", "Save changes")}</Button>
            </footer>
          </form>
        </ViewportModal>
      )}

      {/* DELETE CONFIRM */}
      {isAdmin && selectedRoom && (
        <ViewportModal
          open={isDeleteOpen}
          onClose={() => setIsDeleteOpen(false)}
          labelledBy="delete-room-title"
          describedBy="delete-room-description"
          busy={roomMutation === "delete"}
          panelClassName="max-w-sm"
        >
          <div className="min-h-0 space-y-6 overflow-y-auto p-6 text-center sm:p-8">
            <div className="w-16 h-16 bg-red-50 text-red-600 rounded-full flex items-center justify-center mx-auto">
              <svg className="w-8 h-8" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                <line x1="12" y1="9" x2="12" y2="13" />
                <line x1="12" y1="17" x2="12.01" y2="17" />
              </svg>
            </div>
            <div className="space-y-2">
              <h3 id="delete-room-title" className="text-xl font-bold text-[#0F2A43]">{localize("Xóa phòng", "Delete room")}</h3>
              <p id="delete-room-description" className="text-sm leading-6 text-[#66727C]">
                {localize("Bạn có chắc muốn xóa phòng", "Are you sure you want to delete room")} <strong>{selectedRoom.roomName}</strong>? {localize("Chỉ phòng chưa từng có đơn đặt hoặc lịch sử bảo trì mới được xóa vĩnh viễn. Phòng đã sử dụng phải được ngừng bán hoặc đưa vào bảo trì để giữ lịch sử vận hành.", "Only a room with no reservation or maintenance history can be permanently deleted. Used rooms must be taken out of sale or placed in maintenance so operational history is preserved.")}
              </p>
            </div>
            {roomFormError && <p role="alert" className="rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-left text-sm font-semibold text-rose-700">{roomFormError}</p>}
            <div className="flex gap-3 pt-2">
              <Button variant="secondary" disabled={roomMutation === "delete"} onClick={() => setIsDeleteOpen(false)} className="flex-1">
                {localize("Hủy", "Cancel")}
              </Button>
              <Button
                variant="danger"
                loading={roomMutation === "delete"}
                loadingLabel={localize("Đang xóa...", "Deleting...")}
                onClick={() => void handleDeleteConfirm()}
                className="flex-1"
              >
                {localize("Xóa", "Delete")}
              </Button>
            </div>
          </div>
        </ViewportModal>
      )}

      {checkoutConfirmation && (
        <ViewportModal
          open
          onClose={() => setCheckoutConfirmation(null)}
          labelledBy="checkout-room-warning-title"
          busy={checkoutRoomId !== null}
          panelClassName="max-w-md"
          backdropClassName="bg-[#091E30]/65"
          zIndexClassName="z-[90]"
        >
          <section className="flex min-h-0 flex-col">
            <header className="border-b border-[#0F2A43]/10 px-6 py-5">
              <p className="text-xs font-bold uppercase tracking-[0.14em] text-rose-700">{localize("Xác nhận trả phòng toàn đơn", "Confirm full reservation checkout")}</p>
              <h2 id="checkout-room-warning-title" className="mt-1 text-xl font-bold text-[#0F2A43]">{localize("Đơn đặt phòng", "Reservation")} {checkoutConfirmation.reservationCode}</h2>
            </header>
            <div className="px-6 py-5 text-sm leading-6 text-[#66727C]">
              {localize("Phòng này thuộc đơn có", "This room belongs to a reservation with")} <strong className="text-[#0F2A43]">{checkoutConfirmation.roomCount} {localize("phòng", "rooms")}</strong>. {localize("Tiếp tục sẽ mở đối soát và trả toàn bộ phòng trong đơn, không chỉ phòng đang chọn.", "Continuing opens reconciliation and checks out the entire reservation, not only the selected room.")}
            </div>
            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4">
              <Button variant="secondary" onClick={() => setCheckoutConfirmation(null)}>{localize("Hủy", "Cancel")}</Button>
              <Button variant="danger" onClick={() => router.push(`/dashboard/reservations?finalPaymentId=${checkoutConfirmation.reservationId}`)}>{localize("Mở đối soát toàn đơn", "Open reservation reconciliation")}</Button>
            </footer>
          </section>
        </ViewportModal>
      )}

      {/* TOAST alerts */}
      {toast && (
        <Toast
          message={toast.message}
          type={toast.type}
          onClose={() => setToast(null)}
        />
      )}
      {/* ───── SHORTCUT CONTEXT MENU DIALOG (US4.4) ───── */}
      {contextMenu && createPortal(
        <div
          ref={contextMenuRef}
          className="fixed z-[120] max-h-[calc(100vh-1.5rem)] w-48 overflow-y-auto rounded-xl border border-gray-200 bg-white py-2 text-xs font-semibold text-text-dark shadow-2xl"
          style={{ top: contextMenu.y, left: contextMenu.x }}
          onClick={(e) => e.stopPropagation()}
        >
          <div className="px-3.5 py-1.5 border-b border-gray-100 bg-gray-50 text-[10px] text-text-light font-bold uppercase tracking-wider">
            {localize("Phòng", "Room")} #{contextMenu.roomName}
          </div>
          
          {/* Check-in / Walk-in via reservation flow */}
          {contextMenu.status === "AVAILABLE" && contextMenu.cleaningStatus === "CLEAN" && (
            <button
              onClick={() => {
                setContextMenu(null);
                router.push(`/dashboard/reservations?walkInRoomId=${contextMenu.roomId}`);
              }}
              className="w-full text-left px-4 py-2 hover:bg-gray-100 text-emerald-700 flex items-center gap-2"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
                <polyline points="22 4 12 14.01 9 11.01" />
              </svg>
              {localize("Tạo khách vãng lai tại phòng này", "Create walk-in for this room")}
            </button>
          )}

          {/* Bảo trì phòng / Chi tiết bảo trì */}
          {contextMenu.status !== "MAINTENANCE" && contextMenu.status !== "CHECKED_IN" ? (
            <button
              onClick={() => {
                const targetRoom = rooms.find(r => r.id === contextMenu.roomId);
                if (targetRoom) {
                  handleOpenMaintenanceForm(targetRoom);
                }
                setContextMenu(null);
              }}
              className="w-full text-left px-4 py-2 hover:bg-gray-100 text-red-650 flex items-center gap-2 border-t border-gray-100"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z" />
              </svg>
              {localize("Chuyển sang bảo trì", "Set maintenance")}
            </button>
          ) : contextMenu.status === "MAINTENANCE" ? (
            <button
              onClick={() => {
                const targetRoom = rooms.find(r => r.id === contextMenu.roomId);
                if (targetRoom) {
                  handleOpenMaintenanceDetail(targetRoom);
                }
                setContextMenu(null);
              }}
              className="w-full text-left px-4 py-2 hover:bg-gray-100 text-blue-650 flex items-center gap-2 border-t border-gray-100"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="11" cy="11" r="8" />
                <line x1="21" y1="21" x2="16.65" y2="16.65" />
              </svg>
              {localize("Chi tiết bảo trì", "Maintenance details")}
            </button>
          ) : null}

          {contextMenu.status === "CHECKED_IN" && (
            <button
              onClick={() => {
                const roomId = contextMenu.roomId;
                setContextMenu(null);
                void openRoomCheckout(roomId);
              }}
              className="flex w-full items-center gap-2 border-t border-gray-100 px-4 py-2 text-left font-bold text-rose-700 hover:bg-rose-50"
            >
              <svg className="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M9 18l6-6-6-6" />
              </svg>
              {localize("Trả phòng / đối soát", "Checkout / reconciliation")}
            </button>
          )}

          {/* Đổi phòng */}
          {contextMenu.status === "CHECKED_IN" && (
            <button
              onClick={() => {
                const targetRoomItem = rooms.find(r => r.id === contextMenu.roomId);
                if (targetRoomItem) {
                  setSwapSourceRoom(targetRoomItem);
                  setSwapTargetRoomName("");
                  setIsSwapOpen(true);
                }
                setContextMenu(null);
              }}
              className="w-full text-left px-4 py-2 hover:bg-gray-100 text-[#B8944F] flex items-center gap-2"
            >
              <svg className="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="23 4 23 10 17 10" />
                <polyline points="1 20 1 14 7 14" />
                <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
              </svg>
              {localize("Chuyển khách sang phòng khác", "Move guest to another room")}
            </button>
          )}

          <div className="border-t border-gray-100 my-1"></div>
          <div className="px-3.5 py-1 text-[9px] text-text-light font-bold uppercase tracking-widest">
            {localize("Vệ sinh phòng", "Housekeeping")}
          </div>
          
          <button
            onClick={() => handleContextCleaningStatus("CLEAN")}
            className={`w-full text-left px-4 py-1.5 hover:bg-gray-100 flex items-center gap-2 ${contextMenu.cleaningStatus === "CLEAN" ? "text-emerald-600 font-bold" : ""}`}
          >
            <span className="w-2 h-2 rounded-full bg-emerald-500" /> {getCleaningStatusLabel("CLEAN")}
          </button>
          <button
            onClick={() => handleContextCleaningStatus("DIRTY")}
            className={`w-full text-left px-4 py-1.5 hover:bg-gray-100 flex items-center gap-2 ${contextMenu.cleaningStatus === "DIRTY" ? "text-orange-700 font-bold" : ""}`}
          >
            <span className="w-2 h-2 rounded-full bg-orange-500" /> {getCleaningStatusLabel("DIRTY")}
          </button>
          <button
            onClick={() => handleContextCleaningStatus("IN_PROGRESS")}
            className={`w-full text-left px-4 py-1.5 hover:bg-gray-100 flex items-center gap-2 ${contextMenu.cleaningStatus === "IN_PROGRESS" ? "text-violet-700 font-bold" : ""}`}
          >
            <span className="w-2 h-2 rounded-full bg-violet-500" /> {getCleaningStatusLabel("IN_PROGRESS")}
          </button>
        </div>,
        document.body,
      )}

      {/* ───── SWAP ROOM MODAL (US4.4) ───── */}
      {isSwapOpen && swapSourceRoom && (
        <ViewportModal
          open
          onClose={() => setIsSwapOpen(false)}
          labelledBy="swap-room-title"
          describedBy="swap-room-description"
          busy={isSwapLoading}
          panelClassName="max-w-md"
          backdropClassName="bg-[#091E30]/62"
        >
          <div className="flex min-h-0 flex-col">
            <header className="border-b border-[#0F2A43]/10 bg-[#F7F4EC] px-5 py-4 sm:px-6">
              <h3 id="swap-room-title" className="text-lg font-bold text-[#0F2A43]">{localize("Chuyển khách sang phòng khác", "Move guest to another room")}</h3>
              <p id="swap-room-description" className="mt-1 text-xs leading-5 text-[#66727C]">{localize(`Chuyển khách khỏi phòng #${swapSourceRoom.roomName} (${localize(swapSourceRoom.roomTypeName, swapSourceRoom.roomTypeNameEn)})`, `Move the guest from room #${swapSourceRoom.roomName} (${localize(swapSourceRoom.roomTypeName, swapSourceRoom.roomTypeNameEn)})`)}</p>
            </header>

            <div className="min-h-0 space-y-3 overflow-y-auto px-5 py-5 text-xs font-semibold text-text-dark sm:px-6">
              <label htmlFor="swap-room-target" className="block text-xs font-bold text-[#0F2A43]">{localize("Chọn phòng sạch đang sẵn sàng", "Choose an available clean room")} *</label>
              <select
                id="swap-room-target"
                data-modal-autofocus
                value={swapTargetRoomName}
                onChange={(e) => setSwapTargetRoomName(e.target.value)}
                className="min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3.5 text-sm font-semibold outline-none focus:border-[#B8944F] focus:ring-2 focus:ring-[#B8944F]/25"
              >
                <option value="">-- {localize("Chọn phòng sạch đang sẵn sàng", "Select a clean available room")} --</option>
                {rooms
                  .filter((room) => room.status === "AVAILABLE" && room.cleaningStatus === "CLEAN" && room.roomTypeId === swapSourceRoom.roomTypeId)
                  .map((room) => (
                    <option key={room.roomName} value={room.roomName}>
                      {localize("Phòng", "Room")} #{room.roomName} ({localize(room.roomTypeName, room.roomTypeNameEn)})
                    </option>
                  ))}
              </select>
              {rooms.filter((room) => room.status === "AVAILABLE" && room.cleaningStatus === "CLEAN" && room.roomTypeId === swapSourceRoom.roomTypeId).length === 0 && (
                <p className="text-[10px] text-red-650 font-bold flex items-center gap-1">
                  <svg className="w-3.5 h-3.5 text-red-650 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                    <line x1="12" y1="9" x2="12" y2="13" />
                    <line x1="12" y1="17" x2="12.01" y2="17" />
                  </svg>
                  {localize("Không có phòng sạch đang sẵn sàng cùng loại phòng.", "No clean available room in the same room type.")}
                </p>
              )}
            </div>

            <footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 bg-[#FBFAF6] px-5 py-4 sm:px-6">
              <Button variant="secondary" disabled={isSwapLoading} onClick={() => setIsSwapOpen(false)}>
                {localize("Đóng", "Close")}
              </Button>
              <Button
                disabled={!swapTargetRoomName || isSwapLoading}
                loading={isSwapLoading}
                loadingLabel={localize("Đang chuyển...", "Moving...")}
                onClick={() => void handleTransferRoom()}
              >
                {localize("Xác nhận chuyển phòng", "Confirm room move")}
              </Button>
            </footer>
          </div>
        </ViewportModal>
      )}
      <CleanConfirmModal
        isOpen={confirmCleanOpen}
        onClose={() => setConfirmCleanOpen(false)}
        roomName={cleanTargetRoom?.roomName}
        targetStatus={cleanTargetStatus}
        onConfirm={handleConfirmCleanSubmit}
      />

      <MaintenanceFormModal
        isOpen={maintenanceFormOpen}
        onClose={() => setMaintenanceFormOpen(false)}
        roomName={maintenanceTargetRoom?.roomName}
        roomTypeName={maintenanceTargetRoom?.roomTypeName}
        onConfirm={async (reason, expectedDate) => {
          if (!maintenanceTargetRoom) return;
          try {
            const response = await apiClient.patch(`/api/rooms/${maintenanceTargetRoom.id}/maintenance/start`, {
              reason,
              expectedCompletedDate: expectedDate,
            });
            const updatedRoom = response.data as RoomItem;
            setRooms((current) => current.map((room) => room.id === updatedRoom.id ? { ...room, ...updatedRoom } : room));
            setMaintenanceFormOpen(false);
            showToast(`Đã bắt đầu bảo trì phòng #${maintenanceTargetRoom.roomName}.`, "success");
          } catch (error: unknown) {
            showToast(getApiErrorMessage(error, "Không thể bắt đầu bảo trì phòng."), "error");
          }
        }}
      />

      <MaintenanceDetailModal
        isOpen={maintenanceDetailOpen}
        onClose={() => setMaintenanceDetailOpen(false)}
        room={maintenanceDetailRoom}
        onAddLog={async (note) => {
          if (!maintenanceDetailRoom) return;
          try {
            const response = await apiClient.post(`/api/rooms/${maintenanceDetailRoom.id}/maintenance/logs`, { note });
            const updatedRoom = response.data as RoomItem;
            setRooms((current) => current.map((room) => room.id === updatedRoom.id ? { ...room, ...updatedRoom } : room));
            setMaintenanceDetailRoom(updatedRoom);
            showToast("Đã lưu nhật ký bảo trì.", "success");
          } catch (error: unknown) {
            showToast(getApiErrorMessage(error, "Không thể lưu nhật ký bảo trì."), "error");
          }
        }}
        onCompleteMaintenance={async () => {
          if (!maintenanceDetailRoom) return;

          try {
            const response = await apiClient.patch(`/api/rooms/${maintenanceDetailRoom.id}/maintenance/complete`);
            const updatedRoom = response.data as RoomItem;
            setRooms((current) => current.map((room) => room.id === updatedRoom.id ? { ...room, ...updatedRoom } : room));
            setMaintenanceDetailOpen(false);
            setMaintenanceDetailRoom(null);
            showToast(`Đã hoàn tất bảo trì phòng #${maintenanceDetailRoom.roomName}; phòng chuyển sang trống bẩn.`, "success");
          } catch (error: unknown) {
            showToast(getApiErrorMessage(error, "Không thể hoàn tất bảo trì."), "error");
          }
        }}
      />
    </div>
  );
}
