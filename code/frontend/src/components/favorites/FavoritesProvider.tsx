"use client";

import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

const FAVORITES_STORAGE_KEY = "hotel_favorite_room_type_ids";

interface FavoritesContextValue {
  favoriteRoomIds: number[];
  favoriteCount: number;
  isReady: boolean;
  isFavorite: (roomId: number) => boolean;
  toggleFavorite: (roomId: number) => void;
  reconcileFavorites: (availableRoomIds: number[]) => void;
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
    try {
      setFavoriteRoomIds(parseFavoriteIds(localStorage.getItem(FAVORITES_STORAGE_KEY)));
    } catch {
      setFavoriteRoomIds([]);
    }
    setIsReady(true);

    const handleStorage = (event: StorageEvent) => {
      if (event.key === FAVORITES_STORAGE_KEY) {
        setFavoriteRoomIds(parseFavoriteIds(event.newValue));
      }
    };
    window.addEventListener("storage", handleStorage);
    return () => window.removeEventListener("storage", handleStorage);
  }, []);

  useEffect(() => {
    if (!isReady) return;
    try {
      localStorage.setItem(FAVORITES_STORAGE_KEY, JSON.stringify(favoriteRoomIds));
    } catch {
      // Favorites remain usable for the current session when storage is blocked.
    }
  }, [favoriteRoomIds, isReady]);

  const updateFavorites = useCallback((updater: (current: number[]) => number[]) => {
    setFavoriteRoomIds((current) => updater(current));
  }, []);

  const toggleFavorite = useCallback((roomId: number) => {
    if (!Number.isInteger(roomId) || roomId <= 0) return;
    updateFavorites((current) => current.includes(roomId)
      ? current.filter((id) => id !== roomId)
      : [...current, roomId]);
  }, [updateFavorites]);

  const reconcileFavorites = useCallback((availableRoomIds: number[]) => {
    const availableIds = new Set(
      availableRoomIds.map(Number).filter((id) => Number.isInteger(id) && id > 0),
    );
    updateFavorites((current) => {
      const reconciled = current.filter((id) => availableIds.has(id));
      return reconciled.length === current.length ? current : reconciled;
    });
  }, [updateFavorites]);

  const clearFavorites = useCallback(() => updateFavorites(() => []), [updateFavorites]);
  const isFavorite = useCallback((roomId: number) => favoriteRoomIds.includes(roomId), [favoriteRoomIds]);

  const value = useMemo(() => ({
    favoriteRoomIds,
    favoriteCount: favoriteRoomIds.length,
    isReady,
    isFavorite,
    toggleFavorite,
    reconcileFavorites,
    clearFavorites,
  }), [clearFavorites, favoriteRoomIds, isFavorite, isReady, reconcileFavorites, toggleFavorite]);

  return <FavoritesContext.Provider value={value}>{children}</FavoritesContext.Provider>;
}

export function useFavorites() {
  const context = useContext(FavoritesContext);
  if (!context) throw new Error("useFavorites must be used inside FavoritesProvider");
  return context;
}
