"use client";

import React, { useState, useEffect } from "react";

export interface PhysicalRoom {
  roomName: string;
  status: "AVAILABLE" | "BOOKED" | "CHECKED_IN" | "MAINTENANCE";
  cleaningStatus: "CLEAN" | "DIRTY" | "IN_PROGRESS";
  roomTypeName: string;
}

interface CheckInModalProps {
  isOpen: boolean;
  onClose: () => void;
  reservation: {
    id: string;
    guestName: string;
    roomType: string;
    checkIn: string;
    checkOut: string;
    nights: number;
    total: number;
  } | null;
  physicalRooms: PhysicalRoom[];
  onComplete: (
    repGuestName: string,
    idType: string,
    idNumber: string,
    assignedRoomNumber: string
  ) => void;
}

export default function CheckInModal({
  isOpen,
  onClose,
  reservation,
  physicalRooms,
  onComplete,
}: CheckInModalProps) {
  const [repGuestName, setRepGuestName] = useState("");
  const [idType, setIdType] = useState("CCCD");
  const [idNumber, setIdNumber] = useState("");
  const [assignedRoomNumber, setAssignedRoomNumber] = useState("");

  useEffect(() => {
    if (reservation) {
      setRepGuestName(reservation.guestName);
      setIdType("CCCD");
      setIdNumber("");
      
      const cleanVacant = physicalRooms.filter(
        (room) => room.status === "AVAILABLE" && room.cleaningStatus === "CLEAN"
      );
      if (cleanVacant.length > 0) {
        setAssignedRoomNumber(cleanVacant[0].roomName);
      } else {
        setAssignedRoomNumber("");
      }
    }
  }, [reservation, physicalRooms]);

  if (!isOpen || !reservation) return null;

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!repGuestName.trim() || !idNumber.trim() || !assignedRoomNumber) {
      alert("Họ tên, số giấy tờ và gán phòng vật lý là bắt buộc!");
      return;
    }
    onComplete(repGuestName, idType, idNumber, assignedRoomNumber);
  };

  const availableCleanRooms = physicalRooms.filter(
    (room) => room.status === "AVAILABLE" && room.cleaningStatus === "CLEAN"
  );

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <form
        onSubmit={handleSubmit}
        className="bg-white border border-gray-200 rounded-xl shadow-2xl max-w-md w-full overflow-hidden"
      >
        {/* Header */}
        <div className="bg-[#0F2A43] p-6 text-white text-center">
          <h3 className="font-serif text-xl font-bold tracking-wide">Quy trình Check-in</h3>
          <p className="text-[10px] font-semibold text-[#C8A35B] uppercase tracking-wider mt-1">
            Đơn đặt phòng: {reservation.id}
          </p>
        </div>

        {/* Body */}
        <div className="p-6 space-y-4 text-xs font-semibold text-text-dark">
          {/* Summary Details */}
          <div className="border border-gray-200/60 p-4 rounded-lg bg-gray-50/50 space-y-1.5 text-text-light">
            <p>
              Khách hàng: <span className="text-text-dark font-bold">{reservation.guestName}</span>
            </p>
            <p>
              Hạng phòng: <span className="text-text-dark font-bold">{reservation.roomType}</span>
            </p>
            <p>
              Thời gian lưu trú:{" "}
              <span className="text-text-dark font-bold">
                {reservation.checkIn} → {reservation.checkOut} ({reservation.nights} đêm)
              </span>
            </p>
          </div>

          {/* Form Input */}
          <div className="space-y-3.5">
            <div>
              <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1.5">
                Họ tên khách đại diện *
              </label>
              <input
                type="text"
                value={repGuestName}
                onChange={(e) => setRepGuestName(e.target.value)}
                placeholder="Nguyễn Văn A"
                className="w-full border border-gray-300 px-3.5 py-2.5 rounded-xl text-sm font-medium focus:outline-none focus:ring-2 focus:ring-[#C8A35B]/50 focus:border-[#C8A35B]"
                required
              />
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1.5">
                  Loại giấy tờ tùy thân *
                </label>
                <select
                  value={idType}
                  onChange={(e) => setIdType(e.target.value)}
                  className="w-full border border-gray-300 px-3 py-2.5 rounded-xl text-sm font-medium focus:outline-none bg-transparent"
                >
                  <option value="CCCD">CCCD</option>
                  <option value="Passport">Passport</option>
                  <option value="Giấy khai sinh">Giấy khai sinh</option>
                </select>
              </div>
              <div>
                <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1.5">
                  Số giấy tờ *
                </label>
                <input
                  type="text"
                  value={idNumber}
                  onChange={(e) => setIdNumber(e.target.value)}
                  placeholder="Nhập số giấy tờ"
                  className="w-full border border-gray-300 px-3.5 py-2.5 rounded-xl text-sm font-medium focus:outline-none focus:ring-2 focus:ring-[#C8A35B]/50 focus:border-[#C8A35B]"
                  required
                />
              </div>
            </div>

            <div>
              <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1.5">
                Gán phòng vật lý trống sạch *
              </label>
              <select
                value={assignedRoomNumber}
                onChange={(e) => setAssignedRoomNumber(e.target.value)}
                className="w-full border border-gray-300 px-3 py-2.5 rounded-xl text-sm font-medium focus:outline-none bg-transparent"
                required
              >
                <option value="" disabled>-- Chọn số phòng vật lý --</option>
                {availableCleanRooms.map((room) => (
                  <option key={room.roomName} value={room.roomName}>
                    Phòng #{room.roomName} ({room.roomTypeName})
                  </option>
                ))}
              </select>
              {availableCleanRooms.length === 0 && (
                <p className="text-[10px] text-red-600 mt-1 font-bold flex items-center gap-1">
                  <svg className="w-3.5 h-3.5 text-red-600 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                    <line x1="12" y1="9" x2="12" y2="13" />
                    <line x1="12" y1="17" x2="12.01" y2="17" />
                  </svg>
                  Hiện tại không có phòng trống sạch khả dụng!
                </p>
              )}
            </div>
          </div>
        </div>

        {/* Footer Buttons */}
        <div className="bg-[#F1F0EA] px-6 py-4 flex gap-3 border-t border-gray-200 justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4.5 py-2.5 border border-gray-350 text-xs font-bold tracking-widest uppercase transition-colors hover:bg-gray-100 rounded-xl"
          >
            Hủy bỏ
          </button>
          <button
            type="submit"
            disabled={availableCleanRooms.length === 0}
            className="px-5 py-2.5 bg-[#0F2A43] hover:bg-[#091E30] text-white text-xs font-bold tracking-widest uppercase transition-colors rounded-xl shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Hoàn tất Check-in
          </button>
        </div>
      </form>
    </div>
  );
}
