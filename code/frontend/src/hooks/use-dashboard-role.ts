"use client";

import { useEffect, useState } from "react";
import { cachedGet } from "@/lib/api";

export type DashboardRole = "ADMIN" | "STAFF" | "CUSTOMER" | null;

type DashboardUserProfile = {
  type?: string;
  role?: string;
};

const normalizeRole = (profile?: DashboardUserProfile): DashboardRole => {
  const value = String(profile?.role || profile?.type || "")
    .replace("ROLE_", "")
    .toUpperCase();
  return value === "ADMIN" || value === "STAFF" || value === "CUSTOMER" ? value : null;
};

export function useDashboardRole() {
  const [role, setRole] = useState<DashboardRole>(null);

  useEffect(() => {
    let active = true;

    const loadRole = async () => {
      try {
        const response = await cachedGet<{ data?: DashboardUserProfile }>("/api/user/me", { ttlMs: 5_000 });
        if (active) setRole(normalizeRole(response.data?.data));
      } catch {
        if (active) setRole(null);
      }
    };

    void loadRole();
    return () => {
      active = false;
    };
  }, []);

  return {
    role,
    isAdmin: role === "ADMIN",
    isStaff: role === "STAFF",
  };
}
