"use client";

import React, { useEffect, useMemo, useState } from "react";
import { apiClient, getApiErrorMessage, publicApiClient } from "@/lib/api";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { clearIdempotencyKey, getOrCreateIdempotencyKey } from "@/lib/idempotency";
import BankAccountFields from "@/components/forms/BankAccountFields";

export type RefundRoute = "NONE" | "VNPAY_ORIGINAL" | "MANUAL_BANK_TRANSFER" | "CASH_AT_COUNTER" | "MIXED";
export type RefundDestinationStatus = "NOT_REQUIRED" | "REQUIRED" | "SUBMITTED" | "VERIFIED";
type RefundRecipientApiStatus = RefundDestinationStatus | "REJECTED" | "SUPERSEDED";

interface RefundRecipientPayload {
  bankCode?: string;
  bankName?: string;
  accountNumberMasked?: string;
  accountHolderNameMasked?: string;
  status?: RefundRecipientApiStatus;
  required?: boolean;
  refundBankSummary?: string;
  refundDestinationStatus?: RefundDestinationStatus;
}

interface RefundRecipientFormProps {
  reservationId: number;
  route?: RefundRoute;
  status?: RefundDestinationStatus;
  bankSummary?: string;
  guestToken?: string;
  onSaved?: (recipient: RefundRecipientPayload) => void;
}

const unwrapData = <T,>(payload: unknown): T => {
  if (payload && typeof payload === "object" && "data" in payload) {
    return (payload as { data: T }).data;
  }
  return payload as T;
};

export default function RefundRecipientForm({
  reservationId,
  route,
  status = "REQUIRED",
  bankSummary,
  guestToken,
  onSaved,
}: RefundRecipientFormProps) {
  const { localize } = useLanguage();
  const [bankCode, setBankCode] = useState("");
  const [bankName, setBankName] = useState("");
  const [accountNumber, setAccountNumber] = useState("");
  const [accountHolderName, setAccountHolderName] = useState("");
  const [summary, setSummary] = useState(bankSummary || "");
  const [currentStatus, setCurrentStatus] = useState<RefundRecipientApiStatus>(status);
  const [isEditing, setIsEditing] = useState(status === "REQUIRED");
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState("");
  const [success, setSuccess] = useState("");

  const requiresManualDestination = route === "MANUAL_BANK_TRANSFER" || route === "MIXED";
  const requestConfig = useMemo(
    () => guestToken ? { headers: { "X-Guest-Token": guestToken } } : undefined,
    [guestToken],
  );

  useEffect(() => {
    setCurrentStatus(status);
    setSummary(bankSummary || "");
    setIsEditing(status === "REQUIRED");
  }, [bankSummary, status]);

  useEffect(() => {
    if (!requiresManualDestination || currentStatus === "REQUIRED") return;

    let active = true;
    const loadRecipient = async () => {
      setIsLoading(true);
      setError("");
      try {
        const client = guestToken ? publicApiClient : apiClient;
        const response = await client.get(`/api/reservations/${reservationId}/refund-recipient`, requestConfig);
        const recipient = unwrapData<RefundRecipientPayload>(response.data);
        if (!active || !recipient) return;
        setBankCode(recipient.bankCode || "");
        setBankName(recipient.bankName || "");
        setSummary(
          recipient.refundBankSummary
          || [recipient.bankName || recipient.bankCode, recipient.accountNumberMasked].filter(Boolean).join(" · "),
        );
        const recipientStatus = recipient.status || recipient.refundDestinationStatus;
        if (recipientStatus) setCurrentStatus(recipientStatus);
        if (recipient.required || recipientStatus === "REJECTED" || recipientStatus === "SUPERSEDED") setIsEditing(true);
      } catch (loadError: unknown) {
        if (active) {
          setError(getApiErrorMessage(loadError, localize("Không thể tải thông tin nhận hoàn tiền.", "Could not load refund recipient details.")));
        }
      } finally {
        if (active) setIsLoading(false);
      }
    };

    void loadRecipient();
    return () => {
      active = false;
    };
  }, [currentStatus, guestToken, localize, requestConfig, requiresManualDestination, reservationId]);

  if (!requiresManualDestination || currentStatus === "NOT_REQUIRED") return null;

  const validate = () => {
    if (!/^[A-Z0-9]{2,20}$/.test(bankCode.trim().toUpperCase())) {
      return localize("Mã ngân hàng gồm 2–20 chữ cái hoặc chữ số.", "Bank code must contain 2–20 letters or numbers.");
    }
    if (bankName.trim().length < 2 || bankName.trim().length > 100) {
      return localize("Tên ngân hàng phải từ 2–100 ký tự.", "Bank name must contain 2–100 characters.");
    }
    if (!/^\d{6,24}$/.test(accountNumber)) {
      return localize("Số tài khoản phải gồm 6–24 chữ số.", "Account number must contain 6–24 digits.");
    }
    if (accountHolderName.trim().length < 2 || accountHolderName.trim().length > 100) {
      return localize("Tên chủ tài khoản phải từ 2–100 ký tự.", "Account holder name must contain 2–100 characters.");
    }
    return "";
  };

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const validationError = validate();
    if (validationError) {
      setError(validationError);
      setSuccess("");
      return;
    }

    setIsSaving(true);
    setError("");
    setSuccess("");
    const operationScope = `reservation:${reservationId}:REFUND_RECIPIENT`;
    try {
      const client = guestToken ? publicApiClient : apiClient;
      const response = await client.put(
        `/api/reservations/${reservationId}/refund-recipient`,
        {
          bankCode: bankCode.trim().toUpperCase(),
          bankName: bankName.trim(),
          accountNumber,
          accountHolderName: accountHolderName.trim().toLocaleUpperCase("vi-VN"),
        },
        {
          ...requestConfig,
          headers: {
            ...(requestConfig?.headers || {}),
            "Idempotency-Key": getOrCreateIdempotencyKey(operationScope),
          },
        },
      );
      clearIdempotencyKey(operationScope);
      const recipient = unwrapData<RefundRecipientPayload>(response.data) || {};
      const nextSummary = recipient.refundBankSummary
        || [recipient.bankName || bankName.trim(), recipient.accountNumberMasked || `•••• ${accountNumber.slice(-4)}`].filter(Boolean).join(" · ");
      const nextStatus: RefundDestinationStatus = recipient.status === "VERIFIED" || recipient.refundDestinationStatus === "VERIFIED" ? "VERIFIED" : "SUBMITTED";
      setSummary(nextSummary);
      setCurrentStatus(nextStatus);
      setAccountNumber("");
      setIsEditing(false);
      setSuccess(localize("Đã lưu thông tin nhận hoàn tiền an toàn.", "Refund recipient details were saved securely."));
      onSaved?.({ ...recipient, refundBankSummary: nextSummary, refundDestinationStatus: nextStatus });
    } catch (saveError: unknown) {
      setError(getApiErrorMessage(saveError, localize("Không thể lưu thông tin nhận hoàn tiền.", "Could not save refund recipient details.")));
    } finally {
      setIsSaving(false);
    }
  };

  if (!isEditing && currentStatus !== "REQUIRED") {
    return (
      <section className="rounded-xl border border-emerald-200 bg-emerald-50/70 p-4" aria-labelledby={`refund-recipient-${reservationId}`}>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h4 id={`refund-recipient-${reservationId}`} className="text-sm font-bold text-emerald-900">
              {currentStatus === "VERIFIED"
                ? localize("Thông tin nhận tiền đã được xác minh", "Refund recipient verified")
                : localize("Đã gửi thông tin nhận tiền", "Refund recipient submitted")}
            </h4>
            <p className="mt-1 text-sm font-medium text-emerald-800">{isLoading ? localize("Đang tải...", "Loading...") : summary || localize("Thông tin tài khoản đã được che bớt để bảo mật.", "Account details are masked for security.")}</p>
          </div>
          {currentStatus !== "VERIFIED" && (
            <button type="button" onClick={() => { setIsEditing(true); setError(""); setSuccess(""); }} className="min-h-11 shrink-0 rounded-lg border border-emerald-700 px-4 text-sm font-bold text-emerald-800 hover:bg-emerald-100 focus:outline-none focus:ring-2 focus:ring-emerald-600/40">
              {localize("Cập nhật", "Update")}
            </button>
          )}
        </div>
        {error && <p role="alert" className="mt-3 text-sm font-medium text-rose-700">{error}</p>}
        {success && <p role="status" className="mt-3 text-sm font-medium text-emerald-800">{success}</p>}
      </section>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="rounded-xl border border-amber-200 bg-amber-50/65 p-4 sm:p-5" aria-labelledby={`refund-recipient-${reservationId}`} noValidate>
      <div>
        <h4 id={`refund-recipient-${reservationId}`} className="text-base font-bold text-[#0F2A43]">
          {localize("Thông tin tài khoản nhận hoàn tiền", "Refund recipient bank account")}
        </h4>
        <p className="mt-1 text-sm leading-6 text-[#66727C]">
          {localize("Bạn chỉ cần chọn ngân hàng, nhập số tài khoản và họ tên chủ tài khoản. Khách sạn không bao giờ yêu cầu OTP, PIN hoặc mật khẩu ngân hàng.", "Only select the bank and enter the account number and account holder name. The hotel will never ask for an OTP, PIN, or banking password.")}
        </p>
      </div>

      <BankAccountFields
        className="mt-4"
        disabled={isSaving}
        bankCode={bankCode}
        bankName={bankName}
        accountNumber={accountNumber}
        accountHolderName={accountHolderName}
        onBankChange={(nextCode, nextName) => {
          setBankCode(nextCode);
          setBankName(nextName);
          setError("");
        }}
        onAccountNumberChange={(value) => {
          setAccountNumber(value);
          setError("");
        }}
        onAccountHolderNameChange={(value) => {
          setAccountHolderName(value);
          setError("");
        }}
        error={Boolean(error)}
      />

      {error && <p role="alert" className="mt-4 rounded-lg border border-rose-200 bg-rose-50 px-3 py-2 text-sm font-medium text-rose-700">{error}</p>}
      {success && <p role="status" className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm font-medium text-emerald-800">{success}</p>}

      <div className="mt-4 flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
        {currentStatus !== "REQUIRED" && (
          <button type="button" disabled={isSaving} onClick={() => setIsEditing(false)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-4 text-sm font-bold text-[#0F2A43] hover:bg-white disabled:opacity-50">
            {localize("Đóng", "Close")}
          </button>
        )}
        <button type="submit" disabled={isSaving} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white hover:bg-[#091E30] focus:outline-none focus:ring-2 focus:ring-[#0F2A43]/35 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60">
          {isSaving ? localize("Đang lưu...", "Saving...") : localize("Lưu thông tin nhận tiền", "Save recipient details")}
        </button>
      </div>
    </form>
  );
}
