"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useDashboardRole } from "@/hooks/use-dashboard-role";

export default function CashierShiftsPage() {
  const router = useRouter();
  const { role, isAdmin } = useDashboardRole();

  useEffect(() => {
    if (!role) return;
    router.replace(isAdmin ? "/dashboard/statistics?tab=cashier" : "/dashboard/work-schedules");
  }, [isAdmin, role, router]);

  if (!role || isAdmin) {
    return <div className="mx-auto mt-8 h-40 w-full max-w-[1500px] animate-pulse rounded-2xl bg-[#0F2A43]/7" aria-label="Đang mở khu vực tài chính phù hợp với vai trò" />;
  }
  return <div className="mx-auto mt-8 h-40 w-full max-w-[1500px] animate-pulse rounded-2xl bg-[#0F2A43]/7" aria-label="Đang mở ca làm việc" />;
}
