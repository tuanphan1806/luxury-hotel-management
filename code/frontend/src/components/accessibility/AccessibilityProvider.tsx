"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

const ACCESSIBILITY_STORAGE_KEY = "hotel_accessibility_preferences";

export interface AccessibilityPreferences {
  textSize: "standard" | "large";
  highContrast: boolean;
  reduceMotion: boolean;
  underlineLinks: boolean;
}

interface AccessibilityContextValue {
  preferences: AccessibilityPreferences;
  isReady: boolean;
  updatePreferences: (next: Partial<AccessibilityPreferences>) => void;
  resetPreferences: () => void;
}

const defaultPreferences: AccessibilityPreferences = {
  textSize: "standard",
  highContrast: false,
  reduceMotion: false,
  underlineLinks: false,
};

const AccessibilityContext = createContext<AccessibilityContextValue | null>(null);

const parsePreferences = (value: string | null): AccessibilityPreferences => {
  if (!value) return defaultPreferences;
  try {
    const parsed = JSON.parse(value) as Partial<AccessibilityPreferences>;
    return {
      textSize: parsed.textSize === "large" ? "large" : "standard",
      highContrast: Boolean(parsed.highContrast),
      reduceMotion: Boolean(parsed.reduceMotion),
      underlineLinks: Boolean(parsed.underlineLinks),
    };
  } catch {
    return defaultPreferences;
  }
};

const applyPreferences = (preferences: AccessibilityPreferences) => {
  const root = document.documentElement;
  root.dataset.hotelTextSize = preferences.textSize;
  root.classList.toggle("hotel-high-contrast", preferences.highContrast);
  root.classList.toggle("hotel-reduce-motion", preferences.reduceMotion);
  root.classList.toggle("hotel-underline-links", preferences.underlineLinks);
};

export function AccessibilityProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [preferences, setPreferences] = useState<AccessibilityPreferences>(defaultPreferences);
  const [isReady, setIsReady] = useState(false);

  useEffect(() => {
    const stored = parsePreferences(localStorage.getItem(ACCESSIBILITY_STORAGE_KEY));
    setPreferences(stored);
    applyPreferences(stored);
    setIsReady(true);

    const handleStorage = (event: StorageEvent) => {
      if (event.key !== ACCESSIBILITY_STORAGE_KEY) return;
      const next = parsePreferences(event.newValue);
      setPreferences(next);
      applyPreferences(next);
    };
    window.addEventListener("storage", handleStorage);
    return () => window.removeEventListener("storage", handleStorage);
  }, []);

  const updatePreferences = useCallback((next: Partial<AccessibilityPreferences>) => {
    setPreferences((current) => {
      const updated = { ...current, ...next };
      localStorage.setItem(ACCESSIBILITY_STORAGE_KEY, JSON.stringify(updated));
      applyPreferences(updated);
      return updated;
    });
  }, []);

  const resetPreferences = useCallback(() => {
    localStorage.setItem(ACCESSIBILITY_STORAGE_KEY, JSON.stringify(defaultPreferences));
    setPreferences(defaultPreferences);
    applyPreferences(defaultPreferences);
  }, []);

  const value = useMemo(() => ({ preferences, isReady, updatePreferences, resetPreferences }), [isReady, preferences, resetPreferences, updatePreferences]);

  return <AccessibilityContext.Provider value={value}>{children}</AccessibilityContext.Provider>;
}

export function useAccessibility() {
  const context = useContext(AccessibilityContext);
  if (!context) throw new Error("useAccessibility must be used inside AccessibilityProvider");
  return context;
}
