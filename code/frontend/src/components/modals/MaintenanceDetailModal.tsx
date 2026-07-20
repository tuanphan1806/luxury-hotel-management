"use client";

import React, { useState } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";

interface MaintenanceHistoryLog {
  date: string;
  action: string;
  note: string;
}

interface MaintenanceDetailModalProps {
  isOpen: boolean;
  onClose: () => void;
  room: {
    roomName: string;
    roomTypeName: string;
    maintenanceReason?: string;
    maintenanceExpectedCompletedDate?: string;
    maintenanceHistory?: MaintenanceHistoryLog[];
  } | null;
  onAddLog: (note: string) => void;
  onCompleteMaintenance: () => void;
}

export default function MaintenanceDetailModal({
  isOpen,
  onClose,
  room,
  onAddLog,
  onCompleteMaintenance,
}: MaintenanceDetailModalProps) {
  const { localize } = useLanguage();
  const [logNote, setLogNote] = useState("");

  if (!isOpen || !room) return null;

  const handleLogSubmit = () => {
    if (!logNote.trim()) return;
    onAddLog(logNote);
    setLogNote("");
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="bg-white border border-gray-200 rounded-xl shadow-2xl max-w-2xl w-full overflow-hidden">
        {/* Header */}
        <div className="bg-red-950 p-6 text-white text-center flex justify-between items-center">
          <div className="text-left">
            <h3 className="font-serif text-xl font-bold tracking-wide">
              {localize("Chi tiết bảo trì phòng", "Room maintenance details")} #{room.roomName}
            </h3>
            <p className="text-[10px] font-semibold text-[#C8A35B] uppercase tracking-wider mt-1">
              {room.roomTypeName}
            </p>
          </div>
          <span className="bg-red-900/50 border border-red-500/30 px-3 py-1 rounded-full text-[10px] font-bold tracking-widest uppercase">
            {localize("Đang bảo trì", "Under maintenance")}
          </span>
        </div>

        {/* Body */}
        <div className="p-6 space-y-5 text-xs font-semibold text-text-dark max-h-[60vh] overflow-y-auto">
          {/* Reason & Expected Date */}
          <div className="grid grid-cols-2 gap-4 bg-red-50/20 border border-red-200/50 p-4 rounded-xl">
            <div>
              <p className="text-[9px] text-text-light font-bold uppercase tracking-wider mb-0.5">
                {localize("Lý do bảo trì", "Maintenance reason")}
              </p>
              <p className="text-sm font-bold text-red-900">{room.maintenanceReason}</p>
            </div>
            <div>
              <p className="text-[9px] text-text-light font-bold uppercase tracking-wider mb-0.5">
                {localize("Ngày hoàn thành dự kiến", "Expected completion date")}
              </p>
              <p className="text-sm text-text-dark">{room.maintenanceExpectedCompletedDate}</p>
            </div>
          </div>

          {/* History logs */}
          <div className="space-y-2">
            <p className="text-[10px] text-text-light font-bold uppercase tracking-wider border-b border-gray-150 pb-1">
              {localize("Nhật ký bảo trì", "Maintenance history")}
            </p>
            <div className="overflow-hidden border border-gray-200 rounded-lg">
              <table className="w-full text-left border-collapse text-[11px]">
                <thead>
                  <tr className="bg-gray-50 border-b border-gray-200 text-[10px] uppercase text-text-light">
                    <th className="py-2 px-4">{localize("Thời gian", "Time")}</th>
                    <th className="py-2 px-4">{localize("Thao tác", "Action")}</th>
                    <th className="py-2 px-4">{localize("Chi tiết ghi chú", "Notes")}</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-150 font-medium text-text-dark">
                  {(room.maintenanceHistory || []).map((log, index) => (
                    <tr key={index} className="hover:bg-gray-50/50">
                      <td className="py-2.5 px-4 font-mono text-[10px] text-text-light">{log.date}</td>
                      <td className="py-2.5 px-4">
                        <span
                          className={`px-2 py-0.5 rounded text-[9px] font-bold border ${
                            log.action.includes("Khởi tạo")
                              ? "bg-red-50 text-red-700 border-red-100"
                              : log.action.includes("Hoàn tất")
                              ? "bg-emerald-50 text-emerald-700 border-emerald-100"
                              : "bg-blue-50 text-blue-700 border-blue-100"
                          }`}
                        >
                          {log.action}
                        </span>
                      </td>
                      <td className="py-2.5 px-4 text-text-dark">{log.note}</td>
                    </tr>
                  ))}
                  {(room.maintenanceHistory || []).length === 0 && (
                    <tr>
                      <td colSpan={3} className="py-4 text-center text-text-light font-bold">
                        {localize("Chưa ghi nhận lịch sử bảo trì.", "No maintenance history recorded.")}
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>

          {/* Add progress note */}
          <div className="space-y-2 pt-2 bg-gray-50 border border-gray-200 p-4 rounded-xl">
            <label className="block text-[10px] text-text-light font-bold uppercase tracking-wider mb-1">
              {localize("Cập nhật tiến độ sửa chữa", "Update repair progress")}
            </label>
            <div className="flex gap-2">
              <input
                type="text"
                value={logNote}
                onChange={(e) => setLogNote(e.target.value)}
                placeholder={localize("Nhập ghi chú cập nhật tiến độ...", "Enter a progress update...")}
                className="w-full border border-gray-300 px-3.5 py-2 rounded-xl text-xs font-semibold focus:outline-none"
              />
              <button
                type="button"
                onClick={handleLogSubmit}
                className="px-4 py-2 bg-blue-650 hover:bg-blue-700 text-white font-bold uppercase tracking-widest text-[9px] rounded-xl transition-colors shrink-0 shadow-xs"
              >
                {localize("Cập nhật", "Update")}
              </button>
            </div>
          </div>
        </div>

        {/* Footer */}
        <div className="bg-[#F1F0EA] px-6 py-4 flex gap-3 border-t border-gray-200 justify-end text-xs font-semibold">
          <button
            type="button"
            onClick={onClose}
            className="px-4.5 py-2.5 border border-gray-350 hover:bg-gray-105 rounded-xl uppercase transition-colors"
          >
            {localize("Đóng", "Close")}
          </button>
          <button
            type="button"
            onClick={onCompleteMaintenance}
            className="px-5 py-2.5 bg-emerald-650 hover:bg-emerald-700 text-white font-bold tracking-widest uppercase transition-colors rounded-xl shadow-sm"
          >
            {localize("Hoàn tất bảo trì", "Complete maintenance")}
          </button>
        </div>
      </div>
    </div>
  );
}
