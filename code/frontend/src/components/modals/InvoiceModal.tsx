"use client";

import React from "react";

interface CheckoutData {
  paymentMethod: string;
  amountPaid: number;
  totalAmount: number;
  tax: number;
  serviceCharge: number;
  roomCharge: number;
  minibarCharge: number;
  restaurantCharge: number;
  laundryCharge: number;
}

interface InvoiceModalProps {
  isOpen: boolean;
  onClose: () => void;
  reservation: {
    id: string;
    guestName: string;
    roomNumber: string;
    nights: number;
  } | null;
  folioCode: string;
  checkoutData: CheckoutData | null;
}

// Math-to-text algorithm for Vietnamese numbers
function numberToVietnameseWords(num: number): string {
  if (num === 0) return "Không đồng";
  const units = ["", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"];
  const tens = ["", "mười", "hai mươi", "ba mươi", "bốn mươi", "năm mươi", "sáu mươi", "bảy mươi", "tám mươi", "chín mươi"];
  const blocks = ["", "nghìn", "triệu", "tỷ"];

  const readThreeDigits = (n: number, showZeroHundred: boolean): string => {
    const hundred = Math.floor(n / 100);
    const rem = n % 100;
    const ten = Math.floor(rem / 10);
    const unit = rem % 10;
    let res = "";

    if (hundred > 0 || showZeroHundred) {
      res += units[hundred] + " trăm ";
    }

    if (ten > 0) {
      res += tens[ten] + " ";
    } else if (hundred > 0 && unit > 0) {
      res += "lẻ ";
    }

    if (unit > 0) {
      if (unit === 1 && ten > 1) {
        res += "mốt";
      } else if (unit === 5 && ten > 0) {
        res += "lăm";
      } else {
        res += units[unit];
      }
    }

    return res.trim();
  };

  let words = "";
  let blockIdx = 0;
  let temp = num;

  while (temp > 0) {
    const rem = temp % 1000;
    if (rem > 0) {
      const blockText = readThreeDigits(rem, temp >= 1000) + " " + blocks[blockIdx];
      words = blockText.trim() + " " + words;
    }
    temp = Math.floor(temp / 1000);
    blockIdx++;
  }

  const finalStr = words.trim();
  return finalStr.charAt(0).toUpperCase() + finalStr.slice(1) + " đồng chẵn";
}

export default function InvoiceModal({
  isOpen,
  onClose,
  reservation,
  folioCode,
  checkoutData,
}: InvoiceModalProps) {
  if (!isOpen || !reservation || !checkoutData) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/62 p-4 animate-fade-in print:static print:bg-white print:p-0" role="dialog" aria-modal="true" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div className="bg-white border border-gray-200 rounded-xl shadow-2xl max-w-2xl w-full overflow-hidden print:border-none print:shadow-none print:max-w-none">
        
        {/* Print Container */}
        <div className="p-8 sm:p-10 space-y-6 print:p-0">
          
          {/* Header */}
          <div className="flex justify-between items-start border-b border-gray-200 pb-5 print:border-b-2">
            <div>
              <h2 className="font-serif text-2xl font-bold tracking-widest text-[#0F2A43] uppercase">
                Luxury Hotels Group
              </h2>
              <p className="text-[9px] text-text-light font-bold uppercase tracking-wider mt-1">
                Hanoi Heritage Branch
              </p>
              <p className="text-[8px] text-text-light mt-0.5 font-medium leading-relaxed">
                Địa chỉ: 12 Lý Thường Kiệt, Hoàn Kiếm, Hà Nội
                <br />
                Mã số thuế / Hotel Tax ID: 0101234567
              </p>
            </div>
            <div className="text-right">
              <h3 className="font-serif text-lg font-bold text-rose-900 uppercase tracking-wider">
                Hóa Đơn Giá Trị Gia Tăng
              </h3>
              <p className="text-[9px] text-text-light font-bold uppercase tracking-wider mt-1">
                Official VAT Invoice
              </p>
              <p className="font-mono text-xs text-text-dark mt-1 font-bold">
                Invoice No: INV-{folioCode}
              </p>
            </div>
          </div>

          {/* Guest particulars */}
          <div className="grid grid-cols-2 gap-4 text-[10px] font-bold text-text-dark border-b border-gray-200 pb-4">
            <div>
              <p className="text-[8px] text-text-light uppercase tracking-wider mb-0.5">
                Đơn vị mua hàng / Guest Name
              </p>
              <p className="text-xs font-bold text-[#0F2A43]">{reservation.guestName}</p>
            </div>
            <div>
              <p className="text-[8px] text-text-light uppercase tracking-wider mb-0.5">
                Số phòng / Room Assigned
              </p>
              <p className="text-xs text-text-dark">
                Phòng #{reservation.roomNumber.replace("#", "")}
              </p>
            </div>
          </div>

          {/* Breakdown Folio Table */}
          <div className="overflow-hidden border border-gray-200 rounded-lg">
            <table className="w-full text-left border-collapse text-[10px]">
              <thead>
                <tr className="bg-gray-50 border-b border-gray-200 text-[9px] uppercase text-text-light font-bold">
                  <th className="py-2.5 px-4">STT</th>
                  <th className="py-2.5 px-4">Mô tả dịch vụ / Description</th>
                  <th className="py-2.5 px-4 text-right">Số tiền / Amount (VND)</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-150 font-medium text-text-dark">
                <tr>
                  <td className="py-2 px-4">1</td>
                  <td className="py-2 px-4">Tiền thuê phòng lưu trú ({reservation.nights} đêm)</td>
                  <td className="py-2 px-4 text-right">
                    {checkoutData.roomCharge.toLocaleString("vi-VN")} đ
                  </td>
                </tr>
                <tr>
                  <td className="py-2 px-4">2</td>
                  <td className="py-2 px-4">Phí dịch vụ Mini-bar phòng nghỉ</td>
                  <td className="py-2 px-4 text-right">
                    {checkoutData.minibarCharge.toLocaleString("vi-VN")} đ
                  </td>
                </tr>
                <tr>
                  <td className="py-2 px-4">3</td>
                  <td className="py-2 px-4">Dịch vụ ăn uống Nhà hàng</td>
                  <td className="py-2 px-4 text-right">
                    {checkoutData.restaurantCharge.toLocaleString("vi-VN")} đ
                  </td>
                </tr>
                <tr>
                  <td className="py-2 px-4">4</td>
                  <td className="py-2 px-4">Dịch vụ giặt là buồng phòng</td>
                  <td className="py-2 px-4 text-right">
                    {checkoutData.laundryCharge.toLocaleString("vi-VN")} đ
                  </td>
                </tr>
                <tr className="bg-gray-50/50">
                  <td colSpan={2} className="py-2 px-4 text-right font-bold text-text-light uppercase tracking-wider text-[8px]">
                    Cộng tiền dịch vụ (Subtotal)
                  </td>
                  <td className="py-2 px-4 text-right font-bold">
                    {(
                      checkoutData.roomCharge +
                      checkoutData.minibarCharge +
                      checkoutData.restaurantCharge +
                      checkoutData.laundryCharge
                    ).toLocaleString("vi-VN")}{" "}
                    đ
                  </td>
                </tr>
                <tr className="bg-gray-50/50">
                  <td colSpan={2} className="py-2 px-4 text-right font-bold text-text-light uppercase tracking-wider text-[8px]">
                    Phí phục vụ / Service Charge (5%)
                  </td>
                  <td className="py-2 px-4 text-right font-bold">
                    {checkoutData.serviceCharge.toLocaleString("vi-VN")} đ
                  </td>
                </tr>
                <tr className="bg-gray-50/50">
                  <td colSpan={2} className="py-2 px-4 text-right font-bold text-text-light uppercase tracking-wider text-[8px]">
                    Thuế giá trị gia tăng / VAT (10%)
                  </td>
                  <td className="py-2 px-4 text-right font-bold">
                    {checkoutData.tax.toLocaleString("vi-VN")} đ
                  </td>
                </tr>
                <tr className="bg-rose-50/20 text-[#0F2A43] border-t-2 border-gray-250">
                  <td colSpan={2} className="py-3 px-4 text-right font-extrabold uppercase tracking-wider text-[9px]">
                    Total amount due
                  </td>
                  <td className="py-3 px-4 text-right font-extrabold text-sm text-rose-900">
                    {checkoutData.totalAmount.toLocaleString("vi-VN")} đ
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          {/* Vietnamese word output */}
          <div className="bg-gray-50 p-4 border border-gray-200 rounded-lg text-xs leading-relaxed">
            <p className="text-text-light font-bold text-[9px] uppercase tracking-wider mb-1">
              Số tiền viết bằng chữ / Amount in Words:
            </p>
            <p className="font-serif italic text-text-dark font-bold text-sm text-[#0F2A43]">
              {numberToVietnameseWords(checkoutData.totalAmount)}
            </p>
          </div>

          {/* Signatures */}
          <div className="grid grid-cols-2 gap-10 pt-6 text-center text-xs">
            <div className="space-y-16">
              <p className="font-bold text-text-dark">Người mua hàng / Guest Signature</p>
              <div className="h-px w-36 bg-gray-300 mx-auto" />
            </div>
            <div className="space-y-16">
              <p className="font-bold text-text-dark">Người lập hóa đơn / Cashier Signature</p>
              <div className="h-px w-36 bg-gray-300 mx-auto" />
            </div>
          </div>
        </div>

        {/* Print Control Footer */}
        <div className="bg-[#F1F0EA] px-6 py-4 flex gap-3 border-t border-gray-200 justify-end print:hidden">
          <button
            type="button"
            onClick={onClose}
            className="px-5 py-2.5 border border-gray-350 text-xs font-bold tracking-widest uppercase transition-colors hover:bg-gray-100 rounded-xl"
          >
            Đóng
          </button>
          <button
            type="button"
            onClick={() => window.print()}
            className="px-5 py-2.5 bg-rose-900 hover:bg-rose-950 text-white text-xs font-bold tracking-widest uppercase transition-colors rounded-xl shadow-sm flex items-center gap-2"
          >
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polyline points="6 9 6 2 18 2 18 9" />
              <path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2" />
              <rect x="6" y="14" width="12" height="8" />
            </svg>
            In hóa đơn đỏ (VAT)
          </button>
        </div>

      </div>
    </div>
  );
}
