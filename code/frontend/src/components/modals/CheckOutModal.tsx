"use client";

import React, { useState, useEffect } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";

interface CheckOutModalProps {
  isOpen: boolean;
  onClose: () => void;
  reservation: {
    id: string;
    guestName: string;
    roomNumber: string;
    roomType: string;
    checkIn: string;
    checkOut: string;
    nights: number;
    total: number;
  } | null;
  folioCode: string;
  onComplete: (data: {
    paymentMethod: string;
    amountPaid: number;
    totalAmount: number;
    tax: number;
    serviceCharge: number;
    roomCharge: number;
    minibarCharge: number;
    restaurantCharge: number;
    laundryCharge: number;
  }) => void;
}

export default function CheckOutModal({
  isOpen,
  onClose,
  reservation,
  folioCode,
  onComplete,
}: CheckOutModalProps) {
  const { localize } = useLanguage();
  const [paymentMethod, setPaymentMethod] = useState("Cash");
  const [additionalPayment, setAdditionalPayment] = useState("0");

  const roomCharge = reservation ? Math.round(reservation.total * 25000) : 0;
  const minibarCharge = 150000;
  const restaurantCharge = 450000;
  const laundryCharge = 100000;

  const subtotal = roomCharge + minibarCharge + restaurantCharge + laundryCharge;
  const serviceCharge = Math.round(subtotal * 0.05);
  const tax = Math.round((subtotal + serviceCharge) * 0.1);
  const totalAmount = subtotal + serviceCharge + tax;
  const depositAmount = roomCharge; // Prepaid deposit
  const amountDue = totalAmount - depositAmount;

  const currentPaid = parseFloat(additionalPayment) || 0;
  const balance = amountDue - currentPaid;

  useEffect(() => {
    if (isOpen) {
      setPaymentMethod("Cash");
      setAdditionalPayment("0");
    }
  }, [isOpen]);

  if (!isOpen || !reservation) return null;

  const handleConfirm = () => {
    if (balance !== 0) {
      alert("Số dư công nợ phải bằng 0 đ để hoàn tất Check-out!");
      return;
    }
    onComplete({
      paymentMethod,
      amountPaid: currentPaid,
      totalAmount,
      tax,
      serviceCharge,
      roomCharge,
      minibarCharge,
      restaurantCharge,
      laundryCharge,
    });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="bg-white border border-gray-200 rounded-xl shadow-2xl max-w-md w-full overflow-hidden">
        {/* Header */}
        <div className="bg-rose-950 p-6 text-white text-center">
          <h3 className="font-serif text-xl font-bold tracking-wide">{localize("Thanh toán & trả phòng", "Payment & checkout")}</h3>
          <p className="text-[10px] font-semibold text-[#C8A35B] uppercase tracking-wider mt-1">
            Folio ID: {folioCode}
          </p>
        </div>

        {/* Body */}
        <div className="p-6 space-y-4 text-xs font-semibold text-text-dark max-h-[50vh] overflow-y-auto">
          <div className="flex justify-between items-center border-b border-gray-200 pb-2">
            <span className="text-text-light font-bold">{localize("Khách", "Guest")}: {reservation.guestName}</span>
            <span className="text-text-light font-bold">
              {localize("Phòng", "Room")} #{reservation.roomNumber.replace("#", "")}
            </span>
          </div>

          {/* Folio itemization list */}
          <div className="space-y-2 border-b border-gray-200 pb-3">
            <p className="text-[10px] text-text-light font-bold uppercase tracking-wider">
              {localize("Chi tiết hóa đơn", "Folio details")}
            </p>
            <div className="flex justify-between font-medium">
              <span>Tiền phòng ({reservation.nights} đêm)</span>
              <span>{roomCharge.toLocaleString("vi-VN")} đ</span>
            </div>
            <div className="flex justify-between font-medium">
              <span>Phí Mini-bar phát sinh</span>
              <span>{minibarCharge.toLocaleString("vi-VN")} đ</span>
            </div>
            <div className="flex justify-between font-medium">
              <span>Phí Nhà hàng phát sinh</span>
              <span>{restaurantCharge.toLocaleString("vi-VN")} đ</span>
            </div>
            <div className="flex justify-between font-medium">
              <span>Phí Giặt là phát sinh</span>
              <span>{laundryCharge.toLocaleString("vi-VN")} đ</span>
            </div>
            <div className="flex justify-between font-medium pt-1 border-t border-gray-150">
              <span>Phí phục vụ (5%)</span>
              <span>{serviceCharge.toLocaleString("vi-VN")} đ</span>
            </div>
            <div className="flex justify-between font-medium">
              <span>Thuế GTGT / VAT (10%)</span>
              <span>{tax.toLocaleString("vi-VN")} đ</span>
            </div>
            <div className="flex justify-between font-bold text-sm text-primary-navy pt-1.5 border-t border-gray-200">
              <span>{localize("TỔNG HÓA ĐƠN", "INVOICE TOTAL")}</span>
              <span>{totalAmount.toLocaleString("vi-VN")} đ</span>
            </div>
          </div>

          {/* Financial balance */}
          <div className="bg-gray-50 border border-gray-200 p-4 rounded-lg space-y-2 text-text-light">
            <div className="flex justify-between font-medium">
              <span>Số tiền đã đặt cọc / thanh toán trước:</span>
              <span className="text-text-dark font-bold">{depositAmount.toLocaleString("vi-VN")} đ</span>
            </div>
            <div className="flex justify-between font-medium">
              <span>Số tiền còn lại cần thu thêm:</span>
              <span className="text-red-655 font-bold text-sm">{amountDue.toLocaleString("vi-VN")} đ</span>
            </div>

            <div className="grid grid-cols-2 gap-4 pt-2 border-t border-gray-200/60 items-center">
              <div>
                <label className="block text-[10px] font-bold text-text-dark uppercase tracking-wider mb-1">
                  {localize("Phương thức thanh toán *", "Payment method *")}
                </label>
                <select
                  value={paymentMethod}
                  onChange={(e) => setPaymentMethod(e.target.value)}
                  className="w-full border border-gray-300 px-3 py-1.5 rounded-xl text-xs font-semibold focus:outline-none bg-transparent"
                >
                  <option value="Cash">Cash (Tiền mặt)</option>
                  <option value="Credit Card">Credit Card (Thẻ tín dụng)</option>
                  <option value="Bank Transfer">Bank Transfer (Chuyển khoản)</option>
                </select>
              </div>
              <div>
                <label className="block text-[10px] font-bold text-text-dark uppercase tracking-wider mb-1">
                  Số tiền thu thêm *
                </label>
                <div className="flex gap-2">
                  <input
                    type="number"
                    value={additionalPayment}
                    onChange={(e) => setAdditionalPayment(e.target.value)}
                    className="w-full border border-gray-300 px-3 py-1.5 rounded-xl text-xs font-semibold focus:outline-none"
                  />
                  <button
                    type="button"
                    onClick={() => setAdditionalPayment(String(amountDue))}
                    className="px-2 py-1 bg-gray-200 hover:bg-gray-300 text-[10px] font-bold uppercase tracking-wider rounded-xl transition-colors shrink-0"
                  >
                    Đủ
                  </button>
                </div>
              </div>
            </div>

            {/* AC-CO-01 Check */}
            <div className="flex justify-between font-bold border-t border-gray-200/60 pt-3 text-sm">
              <span className="text-text-dark">SỐ DƯ CÔNG NỢ (BALANCE)</span>
              <span className={balance === 0 ? "text-emerald-600" : "text-red-600"}>
                {balance.toLocaleString("vi-VN")} đ
              </span>
            </div>
            {balance !== 0 && (
              <p className="text-[10px] text-red-600 font-bold text-center mt-1 flex items-center justify-center gap-1">
                <svg className="w-3.5 h-3.5 text-red-650 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                  <line x1="12" y1="9" x2="12" y2="13" />
                  <line x1="12" y1="17" x2="12.01" y2="17" />
                </svg>
                Số dư công nợ phải bằng 0 đ để hoàn tất Check-out!
              </p>
            )}
          </div>
        </div>

        {/* Footer Buttons */}
        <div className="bg-[#F1F0EA] px-6 py-4 flex gap-3 border-t border-gray-200 justify-end">
          <button
            type="button"
            onClick={onClose}
            className="px-4.5 py-2.5 border border-gray-350 text-xs font-bold tracking-widest uppercase transition-colors hover:bg-gray-100 rounded-xl"
          >
            Quay lại
          </button>
          <button
            type="button"
            onClick={handleConfirm}
            disabled={balance !== 0}
            className="px-5 py-2.5 bg-rose-600 hover:bg-rose-700 text-white text-xs font-bold tracking-widest uppercase transition-colors rounded-xl shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Xác nhận Check-out
          </button>
        </div>
      </div>
    </div>
  );
}
