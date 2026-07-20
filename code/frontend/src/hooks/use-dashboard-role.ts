"use client";

import { useEffect, useState } from "react";

export type DashboardRole = "ADMIN" | "STAFF" | "CUSTOMER" | null;

export function useDashboardRole() {
  const [role, setRole] = useState<DashboardRole>(null);

  useEffect(() => {
    try {
      const storedUser = JSON.parse(localStorage.getItem("user") || "{}");
      const value = String(storedUser.role || storedUser.type || "").replace("ROLE_", "").toUpperCase();
      setRole(value === "ADMIN" || value === "STAFF" || value === "CUSTOMER" ? value : null);
    } catch {
      setRole(null);
    }
  }, []);

  return {
    role,
    isAdmin: role === "ADMIN",
    isStaff: role === "STAFF",
  };
}
