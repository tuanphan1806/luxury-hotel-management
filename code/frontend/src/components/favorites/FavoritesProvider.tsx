"use client";

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

const FAVORITES_STORAGE_KEY = "hotel_favorite_room_type_ids";

interface FavoritesContextValue {
  favoriteRoomIds: number[];
  favoriteCount: number;
  isReady: boolean;
  isFavorite: (roomId: number) => boolean;
  toggleFavorite: (roomId: number) => void;
  clearFavorites: () => void;
}

const FavoritesContext = createContext<FavoritesContextValue | null>(null);

const parseFavoriteIds = (value: string | null): number[] => {
  if (!value) return [];
  try {
    const parsed = JSON.parse(value);
    if (!Array.isArray(parsed)) return [];
    return Array.from(new Set(parsed.map(Number).filter((id) => Number.isInteger(id) && id > 0)));
  } catch {
    return [];
  }
};

export function FavoritesProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [favoriteRoomIds, setFavoriteRoomIds] = useState<number[]>([]);
  const [isReady, setIsReady] = useState(false);

  useEffect(() => {
    setFavoriteRoomIds(parseFavoriteIds(localStorage.getItem(FAVORITES_STORAGE_KEY)));
    setIsReady(true);

    const handleStorage = (event: StorageEvent) => {
      if (event.key === FAVORITES_STORAGE_KEY) {
        setFavoriteRoomIds(parseFavoriteIds(event.newValue));
      }
    };
    window.addEventListener("storage", handleStorage);
    return () => window.removeEventListener("storage", handleStorage);
  }, []);

  const updateFavorites = useCallback((updater: (current: number[]) => number[]) => {
    setFavoriteRoomIds((current) => {
      const next = updater(current);
      localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify(next));
      return next;
    });
  }, []);

  const toggleFavorite = useCallback((roomId: number) => {
    if (!Number.isInteger(roomId) || roomId <= 0) return;
    updateFavorites((current) => current.includes(roomId)
      ? current.filter((id) => id !== roomId)
      : [...current, roomId]);
  }, [updateFavorites]);

  const clearFavorites = useCallback(() => updateFavorites(() => []), [updateFavorites]);
  const isFavorite = useCallback((roomId: number) => favoriteRoomIds.includes(roomId), [favoriteRoomIds]);

  const value = useMemo(() => ({
    favoriteRoomIds,
    favoriteCount: favoriteRoomIds.length,
    isReady,
    isFavorite,
    toggleFavorite,
    clearFavorites,
  }), [clearFavorites, favoriteRoomIds, isFavorite, isReady, toggleFavorite]);

  return <FavoritesContext.Provider value={value}>{children}</FavoritesContext.Provider>;
}

export function useFavorites() {
  const context = useContext(FavoritesContext);
  if (!context) throw new Error("useFavorites must be used inside FavoritesProvider");
  return context;
}
