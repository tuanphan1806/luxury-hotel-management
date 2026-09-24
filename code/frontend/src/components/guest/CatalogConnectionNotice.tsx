"use client";

import { useEffect, useState, useSyncExternalStore } from "react";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import {
  getCatalogConnectionState,
  getServerCatalogConnectionState,
  subscribeCatalogConnection,
} from "@/lib/public-catalog";

export default function CatalogConnectionNotice() {
  const { localize } = useLanguage();
  const state = useSyncExternalStore(subscribeCatalogConnection, getCatalogConnectionState, getServerCatalogConnectionState);
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    setSlow(false);
    if (state !== "loading") return;
    const timer = window.setTimeout(() => setSlow(true), 4_000);
    return () => window.clearTimeout(timer);
  }, [state]);

  if (state === "idle" || (state === "loading" && !slow)) return null;
  return (
    <div role="status" aria-live="polite" className="relative z-20 border-y border-[#C8A35B]/40 bg-[#FBF5E8] px-6 pb-4 pt-24 text-sm text-[#0F2A43]">
      <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3">
        <p>{state === "loading"
          ? localize("Dữ liệu đang tải chậm. Hệ thống đang thử kết nối lại, bạn vui lòng chờ một chút.", "Data is taking longer to load. We are reconnecting; please wait a moment.")
          : localize("Chưa kết nối được để tải dữ liệu. Bạn có thể chờ một chút rồi tải lại trang hoặc liên hệ lễ tân.", "We could not load the data. Please wait a moment and reload the page, or contact reception.")}</p>
        {state === "unavailable" && <button type="button" onClick={() => window.location.reload()} className="min-h-11 rounded-lg border border-[#0F2A43]/30 px-4 font-semibold focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[#C8A35B]">
          {localize("Tải lại trang", "Reload page")}
        </button>}
      </div>
    </div>
  );
}
