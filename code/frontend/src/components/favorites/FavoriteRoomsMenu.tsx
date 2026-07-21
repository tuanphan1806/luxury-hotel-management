"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import ProgressiveImage from "@/components/UI/ProgressiveImage";
import { useFavorites } from "@/components/favorites/FavoritesProvider";
import { useLanguage } from "@/components/i18n/LanguageProvider";
import { getPublicRoomTypes } from "@/lib/public-catalog";

interface FavoriteRoomSummary {
  id: number;
  typeName: string;
  typeNameEn?: string;
  imageUrl?: string;
  price?: number;
  maxGuests?: number;
}

interface FavoriteRoomsMenuProps {
  open: boolean;
  onClose: () => void;
}

export default function FavoriteRoomsMenu({ open, onClose }: FavoriteRoomsMenuProps) {
  const { favoriteRoomIds, favoriteCount, reconcileFavorites, toggleFavorite } = useFavorites();
  const { localize, locale } = useLanguage();
  const [roomTypes, setRoomTypes] = useState<FavoriteRoomSummary[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [loadError, setLoadError] = useState("");
  const [hasLoaded, setHasLoaded] = useState(false);
  const [retryKey, setRetryKey] = useState(0);

  useEffect(() => {
    if (!open || favoriteCount === 0 || hasLoaded) return;

    let active = true;
    setIsLoading(true);
    setLoadError("");
    getPublicRoomTypes<FavoriteRoomSummary>()
      .then((items) => {
        if (active) {
          setRoomTypes(items);
          reconcileFavorites(items.map((room) => Number(room.id)));
          setHasLoaded(true);
        }
      })
      .catch(() => {
        if (active) {
          setLoadError(localize("Không thể tải danh sách yêu thích.", "Unable to load favorite rooms."));
          setHasLoaded(false);
        }
      })
      .finally(() => {
        if (active) {
          setIsLoading(false);
        }
      });

    return () => {
      active = false;
    };
  }, [favoriteCount, hasLoaded, localize, open, reconcileFavorites, retryKey]);

  useEffect(() => {
    if (favoriteCount > 0) return;
    setRoomTypes([]);
    setLoadError("");
    setHasLoaded(false);
  }, [favoriteCount]);

  const favoriteRooms = useMemo(
    () => roomTypes.filter((room) => favoriteRoomIds.includes(Number(room.id))),
    [favoriteRoomIds, roomTypes],
  );

  if (!open) return null;

  return (
    <section
      id="favorite-rooms-menu"
      aria-label={localize("Phòng yêu thích", "Favorite rooms")}
      className="absolute right-0 top-full z-[80] mt-3 w-[min(92vw,25rem)] overflow-hidden rounded-[1.35rem] border border-[#0F2A43]/16 bg-[#FBFAF6] shadow-[0_26px_70px_rgba(15,42,67,0.24)]"
    >
      <header className="flex items-end justify-between gap-4 bg-[#0F2A43] px-5 py-5 text-white">
        <div>
          <p className="text-[10px] font-bold uppercase tracking-[0.2em] text-[#D8C398]">{localize("Bộ sưu tập của bạn", "Your collection")}</p>
          <h2 className="mt-1 font-serif text-2xl font-bold">{localize("Phòng yêu thích", "Favorite rooms")}</h2>
        </div>
        <span className="rounded-full border border-[#B8944F]/55 bg-white/8 px-3 py-1 text-xs font-bold tabular-nums text-[#F1F0EA]">
          {favoriteCount} {localize("phòng", "rooms")}
        </span>
      </header>

      <div className="max-h-[22rem] space-y-3 overflow-y-auto p-3 lux-scrollbar">
        {isLoading ? (
          Array.from({ length: 2 }).map((_, index) => (
            <div key={index} className="grid grid-cols-[5.5rem_1fr] gap-3 rounded-xl border border-[#0F2A43]/10 bg-white p-2" aria-hidden="true">
              <div className="h-20 rounded-lg skeleton-surface" />
              <div className="space-y-2 py-1"><div className="h-3 w-20 rounded skeleton-surface" /><div className="h-4 w-36 rounded skeleton-surface" /><div className="h-3 w-24 rounded skeleton-surface" /></div>
            </div>
          ))
        ) : loadError ? (
          <div className="rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm font-medium text-rose-700">
            <p>{loadError}</p>
            <button type="button" onClick={() => { setLoadError(""); setRetryKey((value) => value + 1); }} className="mt-3 inline-flex min-h-9 items-center rounded-lg border border-rose-300 bg-white px-3 text-xs font-bold text-rose-700 transition hover:bg-rose-100">
              {localize("Thử tải lại", "Try again")}
            </button>
          </div>
        ) : favoriteRooms.length === 0 ? (
          <div className="px-4 py-8 text-center">
            <span className="mx-auto flex h-12 w-12 items-center justify-center rounded-full border border-[#0F2A43]/12 bg-[#F1F0EA] text-[#80632F]">
              <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" /></svg>
            </span>
            <p className="mt-3 text-sm font-bold text-[#0F2A43]">{localize("Chưa có phòng yêu thích", "No favorite rooms yet")}</p>
            <p className="mt-1 text-xs leading-5 text-[#66727C]">{localize("Chạm biểu tượng tim trên ảnh phòng để lưu lại.", "Select the heart on a room image to save it.")}</p>
          </div>
        ) : (
          favoriteRooms.map((room) => {
            const title = localize(room.typeName, room.typeNameEn);
            return (
              <article key={room.id} className="grid grid-cols-[5.5rem_minmax(0,1fr)] gap-3 rounded-xl border border-[#0F2A43]/12 bg-white p-2">
                <Link href={`/rooms/${room.id}`} onClick={onClose} className="relative block h-20 overflow-hidden rounded-lg bg-[#E5E9ED] focus:outline-none focus:ring-2 focus:ring-[#B8944F]">
                  {room.imageUrl ? <ProgressiveImage src={room.imageUrl} alt={title} fill sizes="88px" className="object-cover" /> : <span className="absolute inset-0 flex items-center justify-center text-[10px] text-[#66727C]">{localize("Chưa có ảnh", "No image")}</span>}
                </Link>
                <div className="min-w-0 py-0.5">
                  <div className="flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="text-[9px] font-bold uppercase tracking-[0.16em] text-[#80632F]">{localize("Hạng phòng", "Room type")}</p>
                      <Link href={`/rooms/${room.id}`} onClick={onClose} className="mt-0.5 block truncate text-sm font-bold text-[#0F2A43] hover:text-[#80632F]">{title}</Link>
                    </div>
                    <button type="button" onClick={() => toggleFavorite(Number(room.id))} aria-label={localize(`Bỏ ${title} khỏi yêu thích`, `Remove ${title} from favorites`)} className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-rose-50 text-rose-600 transition hover:bg-rose-100 focus:outline-none focus:ring-2 focus:ring-rose-300">
                      <svg aria-hidden="true" viewBox="0 0 24 24" className="h-4 w-4" fill="currentColor"><path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8l1.1 1.1L12 21l7.8-7.5 1.1-1.1a5.5 5.5 0 0 0-.1-7.8Z" /></svg>
                    </button>
                  </div>
                  <div className="mt-2 flex items-center justify-between gap-3">
                    <p className="text-xs font-bold tabular-nums text-[#80632F]">{Number(room.price || 0).toLocaleString(locale === "vi" ? "vi-VN" : "en-US")} đ</p>
                    <Link href={`/rooms/${room.id}`} onClick={onClose} className="inline-flex min-h-9 items-center rounded-lg bg-[#0F2A43] px-3 text-[10px] font-bold text-white transition hover:bg-[#091E30]">{localize("Xem chi tiết", "View details")}</Link>
                  </div>
                </div>
              </article>
            );
          })
        )}
      </div>

      <Link href="/rooms?favorites=1" onClick={onClose} className="flex min-h-12 items-center justify-center border-t border-[#0F2A43]/10 bg-[#F1F0EA] px-5 text-sm font-bold text-[#80632F] transition hover:bg-[#EAE2D2]">
        {localize("Xem tất cả phòng", "View all rooms")} <span aria-hidden="true" className="ml-2">→</span>
      </Link>
    </section>
  );
}
