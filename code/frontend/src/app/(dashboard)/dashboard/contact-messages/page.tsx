"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { apiClient, cachedGet } from "@/lib/api";
import Toast from "@/components/UI/Toast";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  DashboardFilterPanel,
  DashboardSearchField,
  DashboardSelectField,
  FilterQuickButton,
} from "@/components/dashboard/DashboardFilterPanel";

type ContactStatus = "NEW" | "READ" | "RESOLVED";

interface ContactMessage {
  id: number;
  name: string;
  email: string;
  phone?: string;
  subject: string;
  message: string;
  status: ContactStatus;
  replySubject?: string;
  replyMessage?: string;
  repliedAt?: string;
  repliedBy?: string;
  createdAt: string;
  updatedAt?: string;
}

const getApiErrorMessage = (error: unknown, fallback: string) => {
  if (typeof error !== "object" || error === null || !("response" in error)) return fallback;
  const response = (error as { response?: { data?: { message?: unknown } } }).response;
  return typeof response?.data?.message === "string" ? response.data.message : fallback;
};

export default function ContactMessagesPage() {
  const { localeTag, localize } = useLanguage();
  const [messages, setMessages] = useState<ContactMessage[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState<"ALL" | ContactStatus>("ALL");
  const [selectedMessage, setSelectedMessage] = useState<ContactMessage | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<ContactMessage | null>(null);
  const [actionId, setActionId] = useState<number | null>(null);
  const [replySubject, setReplySubject] = useState("");
  const [replyMessage, setReplyMessage] = useState("");
  const [isReplying, setIsReplying] = useState(false);
  const [isAdmin, setIsAdmin] = useState(false);
  const [toast, setToast] = useState<{ message: string; type: "success" | "error" | "info" } | null>(null);

  const loadMessages = useCallback(async () => {
    setIsLoading(true);
    setLoadError("");
    try {
      const response = await cachedGet("/api/contact-messages");
      const data = response.data?.data ?? response.data;
      setMessages(Array.isArray(data) ? data : []);
    } catch (error: unknown) {
      setMessages([]);
      setLoadError(getApiErrorMessage(error, localize("Không thể tải yêu cầu liên hệ.", "Could not load contact messages.")));
    } finally {
      setIsLoading(false);
    }
  }, [localize]);

  useEffect(() => {
    const storedUser = localStorage.getItem("user");
    if (storedUser) {
      try {
        const parsed = JSON.parse(storedUser);
        setIsAdmin(String(parsed.role || parsed.type || "").replace("ROLE_", "").toUpperCase() === "ADMIN");
      } catch {
        setIsAdmin(false);
      }
    }
    void loadMessages();
  }, [loadMessages]);

  useEffect(() => {
    if (!selectedMessage && !deleteTarget) return;

    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      if (deleteTarget) {
        setDeleteTarget(null);
      } else {
        setSelectedMessage(null);
      }
    };

    window.addEventListener("keydown", closeOnEscape);
    return () => window.removeEventListener("keydown", closeOnEscape);
  }, [deleteTarget, selectedMessage]);

  const statusLabel = (status: ContactStatus) => ({
    NEW: localize("Mới", "New"),
    READ: localize("Đã đọc", "Read"),
    RESOLVED: localize("Đã xử lý", "Resolved"),
  }[status]);

  const statusClass = (status: ContactStatus) => ({
    NEW: "border-amber-200 bg-amber-50 text-amber-800",
    READ: "border-blue-200 bg-blue-50 text-blue-800",
    RESOLVED: "border-emerald-200 bg-emerald-50 text-emerald-800",
  }[status]);

  const formatDateTime = (value?: string) => {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString(localeTag, {
      day: "2-digit", month: "2-digit", year: "numeric", hour: "2-digit", minute: "2-digit",
    });
  };

  const filteredMessages = useMemo(() => {
    const keyword = searchQuery.trim().toLowerCase();
    const priority: Record<ContactStatus, number> = { NEW: 0, READ: 1, RESOLVED: 2 };
    return messages
      .filter((message) => {
        const matchesStatus = statusFilter === "ALL" || message.status === statusFilter;
        const matchesSearch = !keyword || [message.name, message.email, message.phone, message.subject, message.message]
          .some((value) => value?.toLowerCase().includes(keyword));
        return matchesStatus && matchesSearch;
      })
      .sort((left, right) => priority[left.status] - priority[right.status]
        || new Date(right.createdAt).getTime() - new Date(left.createdAt).getTime());
  }, [messages, searchQuery, statusFilter]);

  const openMessage = (message: ContactMessage) => {
    setSelectedMessage(message);
    setReplySubject(message.replySubject || `Re: ${message.subject}`);
    setReplyMessage("");
  };

  const updateStatus = async (message: ContactMessage, status: ContactStatus) => {
    if (message.status === status) return;
    setActionId(message.id);
    try {
      const response = await apiClient.patch(`/api/contact-messages/${message.id}/status`, { status });
      const updated = response.data?.data as ContactMessage;
      setMessages((current) => current.map((item) => item.id === message.id ? updated : item));
      setSelectedMessage((current) => current?.id === message.id ? updated : current);
      setToast({ message: localize("Đã cập nhật trạng thái liên hệ.", "Contact status updated."), type: "success" });
    } catch (error: unknown) {
      setToast({ message: getApiErrorMessage(error, localize("Không thể cập nhật trạng thái.", "Could not update the status.")), type: "error" });
    } finally {
      setActionId(null);
    }
  };

  const deleteMessage = async () => {
    if (!deleteTarget) return;
    setActionId(deleteTarget.id);
    try {
      await apiClient.delete(`/api/contact-messages/${deleteTarget.id}`);
      setMessages((current) => current.filter((item) => item.id !== deleteTarget.id));
      setSelectedMessage((current) => current?.id === deleteTarget.id ? null : current);
      setDeleteTarget(null);
      setToast({ message: localize("Đã xóa yêu cầu liên hệ.", "Contact message deleted."), type: "success" });
    } catch (error: unknown) {
      setToast({ message: getApiErrorMessage(error, localize("Không thể xóa yêu cầu liên hệ.", "Could not delete the contact message.")), type: "error" });
    } finally {
      setActionId(null);
    }
  };

  const sendReply = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!selectedMessage || !replySubject.trim() || !replyMessage.trim()) return;

    setIsReplying(true);
    try {
      const response = await apiClient.post(`/api/contact-messages/${selectedMessage.id}/reply`, {
        subject: replySubject.trim(),
        message: replyMessage.trim(),
      });
      const updated = response.data?.data as ContactMessage;
      setMessages((current) => current.map((item) => item.id === updated.id ? updated : item));
      setSelectedMessage(updated);
      setReplySubject(updated.replySubject || `Re: ${updated.subject}`);
      setReplyMessage("");
      setToast({ message: localize("Email phản hồi đã được gửi và lưu lại.", "The reply email was sent and recorded."), type: "success" });
    } catch (error: unknown) {
      setToast({ message: getApiErrorMessage(error, localize("Không thể gửi email phản hồi lúc này.", "The reply email could not be sent.")), type: "error" });
    } finally {
      setIsReplying(false);
    }
  };

  const stats = {
    total: messages.length,
    new: messages.filter((item) => item.status === "NEW").length,
    read: messages.filter((item) => item.status === "READ").length,
    resolved: messages.filter((item) => item.status === "RESOLVED").length,
  };

  return (
    <div className="ops-page mx-auto w-full max-w-[1440px] space-y-6 p-4 sm:p-6 lg:p-8">
      <header className="flex flex-col gap-4 border-b border-[#0F2A43]/10 pb-6 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p className="text-xs font-bold uppercase tracking-[0.18em] text-[#80632F]">{localize("Vận hành chăm sóc khách hàng", "Guest service operations")}</p>
          <h1 className="mt-2 text-3xl font-bold tracking-tight text-[#0F2A43]">{localize("Yêu cầu liên hệ", "Contact messages")}</h1>
          <p className="mt-2 text-sm text-[#66727C]">{localize("Tiếp nhận, theo dõi và hoàn tất các yêu cầu gửi từ trang chủ.", "Review, track and resolve requests submitted from the homepage.")}</p>
        </div>
        <button type="button" onClick={() => void loadMessages()} disabled={isLoading} className="min-h-11 self-start rounded-lg border border-[#0F2A43]/20 bg-white px-5 text-sm font-bold text-[#0F2A43] hover:bg-[#E5E9ED] disabled:opacity-50 sm:self-auto">
          {localize("Làm mới", "Refresh")}
        </button>
      </header>

      <section className="grid grid-cols-2 gap-3 lg:grid-cols-4" aria-label={localize("Thống kê yêu cầu liên hệ", "Contact message statistics")}>
        {[
          [localize("Tổng số", "Total"), stats.total, "text-[#0F2A43]"],
          [localize("Mới", "New"), stats.new, "text-amber-700"],
          [localize("Đã đọc", "Read"), stats.read, "text-blue-700"],
          [localize("Đã xử lý", "Resolved"), stats.resolved, "text-emerald-700"],
        ].map(([label, value, color]) => (
          <div key={String(label)} className="rounded-xl border border-[#0F2A43]/10 bg-white p-4">
            <p className="text-xs font-semibold text-[#66727C]">{label}</p>
            <p className={`mt-2 text-3xl font-extrabold tabular-nums ${color}`}>{value}</p>
          </div>
        ))}
      </section>

      <DashboardFilterPanel
        title={localize("Bộ lọc hộp thư", "Inbox filters")}
        description={localize("Tìm theo người gửi, thông tin liên hệ, chủ đề hoặc nội dung", "Search by sender, contact details, subject or message")}
        resultCount={filteredMessages.length}
        resultLabel={localize("yêu cầu phù hợp", "matching requests")}
        resultNote={localize("tin mới được ưu tiên trước", "new messages appear first")}
        hasActiveFilters={Boolean(searchQuery || statusFilter !== "ALL")}
        activeFilterCount={Number(Boolean(searchQuery)) + Number(statusFilter !== "ALL")}
        activeFilterLabel={localize("bộ lọc đang dùng", "active filters")}
        onReset={() => {
          setSearchQuery("");
          setStatusFilter("ALL");
        }}
        resetLabel={localize("Xóa toàn bộ bộ lọc", "Clear all filters")}
        actions={(
          <>
            <FilterQuickButton active={statusFilter === "NEW"} onClick={() => setStatusFilter("NEW")}>{localize("Tin mới", "New")}</FilterQuickButton>
            <FilterQuickButton active={statusFilter === "RESOLVED"} onClick={() => setStatusFilter("RESOLVED")}>{localize("Đã xử lý", "Resolved")}</FilterQuickButton>
          </>
        )}
      >
        <div className="grid gap-4 md:grid-cols-[minmax(0,2fr)_minmax(14rem,1fr)]">
          <DashboardSearchField
            id="contact-search"
            label={localize("Tìm kiếm", "Search")}
            value={searchQuery}
            onChange={setSearchQuery}
            placeholder={localize("Tên, email, điện thoại, chủ đề hoặc nội dung...", "Name, email, phone, subject or message...")}
            clearLabel={localize("Xóa từ khóa", "Clear search")}
          />
          <DashboardSelectField id="contact-status" label={localize("Trạng thái xử lý", "Processing status")} value={statusFilter} onChange={(event) => setStatusFilter(event.target.value as typeof statusFilter)}>
            <option value="ALL">{localize("Tất cả trạng thái", "All statuses")}</option>
            <option value="NEW">{statusLabel("NEW")}</option>
            <option value="READ">{statusLabel("READ")}</option>
            <option value="RESOLVED">{statusLabel("RESOLVED")}</option>
          </DashboardSelectField>
        </div>
      </DashboardFilterPanel>

      <section className="overflow-hidden rounded-xl border border-[#0F2A43]/10 bg-white" aria-labelledby="contact-list-title">

        <div className="flex flex-col gap-2 border-b border-[#0F2A43]/10 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div><h2 id="contact-list-title" className="font-bold text-[#0F2A43]">{localize("Hộp thư liên hệ", "Contact inbox")}</h2><p className="mt-0.5 text-xs text-[#66727C]">{filteredMessages.length} {localize("yêu cầu · Tin mới được ưu tiên trước", "requests · New messages appear first")}</p></div>
          <span className="text-xs font-semibold text-[#66727C] sm:text-right">{localize("Mở yêu cầu để đọc và phản hồi", "Open a request to review and reply")}</span>
        </div>

        {loadError && <div className="m-4 rounded-lg border border-rose-200 bg-rose-50 p-4 text-sm font-medium text-rose-700" role="alert">{loadError}</div>}
        {isLoading ? (
          <div className="space-y-3 p-5">{[1, 2, 3].map((item) => <div key={item} className="h-16 animate-pulse rounded-lg bg-[#E5E9ED]" />)}</div>
        ) : filteredMessages.length === 0 ? (
          <div className="px-6 py-14 text-center"><p className="font-bold text-[#0F2A43]">{localize("Không có yêu cầu phù hợp", "No matching contact messages")}</p><p className="mt-2 text-sm text-[#66727C]">{localize("Các yêu cầu mới từ trang chủ sẽ xuất hiện tại đây.", "New homepage requests will appear here.")}</p></div>
        ) : (
          <>
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full min-w-[940px] text-left text-sm">
                <thead className="bg-[#F1F0EA] text-xs font-bold text-[#66727C]"><tr><th className="w-2 p-0"><span className="sr-only">{localize("Mức ưu tiên", "Priority")}</span></th><th className="px-5 py-3">{localize("Người gửi", "Sender")}</th><th className="px-5 py-3">{localize("Nội dung yêu cầu", "Request")}</th><th className="px-5 py-3">{localize("Tiếp nhận", "Received")}</th><th className="px-5 py-3">{localize("Trạng thái", "Status")}</th><th className="px-5 py-3 text-right">{localize("Xử lý", "Process")}</th></tr></thead>
                <tbody className="divide-y divide-[#0F2A43]/8">{filteredMessages.map((message) => <tr key={message.id} className={`align-top hover:bg-[#FBFAF6] ${message.status === "NEW" ? "bg-amber-50/30" : ""}`}><td className={`w-1 p-0 ${message.status === "NEW" ? "bg-amber-500" : message.status === "READ" ? "bg-blue-400" : "bg-emerald-500"}`} /><td className="px-5 py-4"><div className="flex items-center gap-2"><p className="font-bold text-[#0F2A43]">{message.name}</p>{message.status === "NEW" && <span className="h-2 w-2 rounded-full bg-amber-500" aria-label={localize("Chưa đọc", "Unread")} />}</div><p className="mt-1 text-xs text-[#66727C]">{message.email}</p>{message.phone && <p className="mt-0.5 text-xs text-[#66727C]">{message.phone}</p>}</td><td className="max-w-md px-5 py-4"><p className="font-semibold text-[#27445F]">{message.subject}</p><p className="mt-1 line-clamp-2 text-xs leading-5 text-[#66727C]">{message.message}</p>{message.repliedAt && <p className="mt-2 text-xs font-semibold text-emerald-700">{localize("Đã gửi phản hồi", "Reply sent")} · {formatDateTime(message.repliedAt)}</p>}</td><td className="whitespace-nowrap px-5 py-4 text-xs tabular-nums text-[#66727C]">{formatDateTime(message.createdAt)}</td><td className="px-5 py-4"><span className={`inline-flex rounded-lg border px-2.5 py-1 text-xs font-bold ${statusClass(message.status)}`}>{statusLabel(message.status)}</span></td><td className="px-5 py-4 text-right"><button type="button" onClick={() => openMessage(message)} className="min-h-10 rounded-lg bg-[#0F2A43] px-4 text-xs font-bold text-white hover:bg-[#091E30]">{message.status === "RESOLVED" ? localize("Xem chi tiết", "View details") : localize("Mở & xử lý", "Open & process")}</button></td></tr>)}</tbody>
              </table>
            </div>
            <div className="divide-y divide-[#0F2A43]/10 md:hidden">{filteredMessages.map((message) => <button key={message.id} type="button" onClick={() => openMessage(message)} className={`relative w-full overflow-hidden p-4 text-left ${message.status === "NEW" ? "bg-amber-50/30" : ""}`}><span className={`absolute inset-y-0 left-0 w-1 ${message.status === "NEW" ? "bg-amber-500" : message.status === "READ" ? "bg-blue-400" : "bg-emerald-500"}`} /><div className="flex items-start justify-between gap-3"><div><div className="flex items-center gap-2"><p className="font-bold text-[#0F2A43]">{message.name}</p>{message.status === "NEW" && <span className="h-2 w-2 rounded-full bg-amber-500" />}</div><p className="mt-1 text-xs text-[#66727C]">{formatDateTime(message.createdAt)}</p></div><span className={`rounded-lg border px-2 py-1 text-[10px] font-bold ${statusClass(message.status)}`}>{statusLabel(message.status)}</span></div><p className="mt-3 truncate text-sm font-semibold text-[#27445F]">{message.subject}</p><p className="mt-1 line-clamp-2 text-xs leading-5 text-[#66727C]">{message.message}</p>{message.repliedAt && <p className="mt-2 text-xs font-semibold text-emerald-700">{localize("Đã gửi phản hồi", "Reply sent")}</p>}</button>)}</div>
          </>
        )}
      </section>

      {selectedMessage && (
        <div className="fixed inset-0 z-50 flex items-center justify-center overflow-y-auto bg-[#091E30]/65 p-4" role="dialog" aria-modal="true" aria-labelledby="contact-detail-title" onMouseDown={(event) => { if (event.target === event.currentTarget && actionId === null) setSelectedMessage(null); }}>
          <section className="max-h-[90vh] w-full max-w-2xl overflow-y-auto rounded-xl bg-white shadow-2xl">
            <header className="flex items-start justify-between gap-4 border-b border-[#0F2A43]/10 px-6 py-5"><div><p className="text-xs font-bold uppercase tracking-[0.14em] text-[#80632F]">#{selectedMessage.id} · {formatDateTime(selectedMessage.createdAt)}</p><h2 id="contact-detail-title" className="mt-2 text-xl font-bold text-[#0F2A43]">{selectedMessage.subject}</h2></div><button type="button" onClick={() => setSelectedMessage(null)} aria-label={localize("Đóng", "Close")} className="flex h-10 w-10 items-center justify-center rounded-lg border border-[#0F2A43]/15 text-lg font-bold text-[#66727C]">×</button></header>
            <div className="space-y-5 p-6">
              <div className="grid gap-3 rounded-xl bg-[#FBFAF6] p-4 sm:grid-cols-2"><div><p className="text-xs font-semibold text-[#66727C]">{localize("Người gửi", "Sender")}</p><p className="mt-1 font-bold text-[#0F2A43]">{selectedMessage.name}</p></div><div><p className="text-xs font-semibold text-[#66727C]">{localize("Liên hệ", "Contact")}</p><div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-sm font-semibold"><a className="text-blue-700 hover:underline" href={`mailto:${selectedMessage.email}`}>{selectedMessage.email}</a>{selectedMessage.phone && <a className="text-blue-700 hover:underline" href={`tel:${selectedMessage.phone}`}>{selectedMessage.phone}</a>}</div></div></div>
              <div><p className="text-xs font-semibold text-[#66727C]">{localize("Nội dung yêu cầu", "Message")}</p><p className="mt-2 whitespace-pre-wrap rounded-xl border border-[#0F2A43]/10 p-4 text-sm leading-7 text-[#27445F]">{selectedMessage.message}</p></div>
              <label className="block text-sm font-bold text-[#0F2A43]">{localize("Trạng thái xử lý", "Processing status")}<select value={selectedMessage.status} disabled={actionId === selectedMessage.id} onChange={(event) => void updateStatus(selectedMessage, event.target.value as ContactStatus)} className="mt-2 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-semibold outline-none focus:border-[#B8944F] disabled:opacity-50"><option value="NEW">{statusLabel("NEW")}</option><option value="READ">{statusLabel("READ")}</option><option value="RESOLVED">{statusLabel("RESOLVED")}</option></select></label>
              {selectedMessage.repliedAt && (
                <section className="rounded-xl border border-emerald-200 bg-emerald-50/60 p-4">
                  <p className="text-xs font-bold text-emerald-800">{localize("Phản hồi gần nhất", "Latest reply")} · {formatDateTime(selectedMessage.repliedAt)}</p>
                  <p className="mt-2 text-sm font-bold text-[#0F2A43]">{selectedMessage.replySubject}</p>
                  <p className="mt-2 whitespace-pre-wrap text-sm leading-6 text-[#66727C]">{selectedMessage.replyMessage}</p>
                  <p className="mt-2 text-xs font-medium text-emerald-800">{localize("Người gửi", "Sent by")}: {selectedMessage.repliedBy || "—"}</p>
                </section>
              )}
              <form id="contact-reply-form" onSubmit={sendReply} className="space-y-4 rounded-xl border border-[#0F2A43]/10 bg-[#FBFAF6] p-4">
                <div>
                  <h3 className="text-sm font-bold text-[#0F2A43]">{localize("Soạn email phản hồi", "Compose email reply")}</h3>
                  <p className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Email được gửi trực tiếp từ hệ thống và chỉ được ghi nhận khi nhà cung cấp mail chấp nhận.", "The email is sent by the system and recorded only after the mail provider accepts it.")}</p>
                </div>
                <label className="block text-xs font-bold text-[#66727C]" htmlFor="contact-reply-subject">{localize("Tiêu đề", "Subject")}<input id="contact-reply-subject" maxLength={200} required value={replySubject} onChange={(event) => setReplySubject(event.target.value)} className="mt-2 min-h-11 w-full rounded-lg border border-[#0F2A43]/15 bg-white px-3 text-sm font-medium outline-none focus:border-[#B8944F]" /></label>
                <label className="block text-xs font-bold text-[#66727C]" htmlFor="contact-reply-message">{localize("Nội dung phản hồi", "Reply message")}<textarea id="contact-reply-message" maxLength={5000} required rows={6} value={replyMessage} onChange={(event) => setReplyMessage(event.target.value)} placeholder={localize("Nhập nội dung cần gửi cho khách...", "Write the message to send to the guest...")} className="mt-2 w-full resize-y rounded-lg border border-[#0F2A43]/15 bg-white px-3 py-3 text-sm leading-6 outline-none focus:border-[#B8944F]" /></label>
              </form>
            </div>
            <footer className="flex flex-wrap justify-between gap-3 border-t border-[#0F2A43]/10 px-6 py-4">{isAdmin ? <button type="button" onClick={() => setDeleteTarget(selectedMessage)} className="min-h-11 rounded-lg border border-rose-200 px-4 text-sm font-bold text-rose-700 hover:bg-rose-50">{localize("Xóa yêu cầu", "Delete message")}</button> : <span />}<div className="flex flex-wrap gap-3"><button type="button" onClick={() => setSelectedMessage(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">{localize("Đóng", "Close")}</button><button type="submit" form="contact-reply-form" disabled={isReplying || !replySubject.trim() || !replyMessage.trim()} className="min-h-11 rounded-lg bg-[#0F2A43] px-5 text-sm font-bold text-white disabled:cursor-not-allowed disabled:opacity-50">{isReplying ? localize("Đang gửi...", "Sending...") : localize("Gửi email phản hồi", "Send email reply")}</button></div></footer>
          </section>
        </div>
      )}

      {deleteTarget && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center overflow-y-auto bg-[#091E30]/65 p-4" role="dialog" aria-modal="true" aria-labelledby="delete-contact-title" onMouseDown={(event) => { if (event.target === event.currentTarget && actionId !== deleteTarget.id) setDeleteTarget(null); }}><section className="w-full max-w-md rounded-xl bg-white shadow-2xl"><div className="p-6"><h2 id="delete-contact-title" className="text-xl font-bold text-[#0F2A43]">{localize("Xóa yêu cầu liên hệ?", "Delete contact message?")}</h2><p className="mt-3 text-sm leading-6 text-[#66727C]">{localize("Yêu cầu và toàn bộ nội dung sẽ bị xóa vĩnh viễn. Thao tác này không thể hoàn tác.", "The request and its content will be permanently deleted. This action cannot be undone.")}</p></div><footer className="flex justify-end gap-3 border-t border-[#0F2A43]/10 px-6 py-4"><button type="button" onClick={() => setDeleteTarget(null)} className="min-h-11 rounded-lg border border-[#0F2A43]/20 px-5 text-sm font-bold text-[#0F2A43]">{localize("Hủy", "Cancel")}</button><button type="button" disabled={actionId === deleteTarget.id} onClick={() => void deleteMessage()} className="min-h-11 rounded-lg bg-rose-700 px-5 text-sm font-bold text-white disabled:opacity-50">{localize("Xác nhận xóa", "Delete")}</button></footer></section></div>
      )}

      {toast && <Toast message={toast.message} type={toast.type} onClose={() => setToast(null)} />}
    </div>
  );
}
