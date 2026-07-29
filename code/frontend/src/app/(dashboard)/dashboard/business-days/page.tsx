import { redirect } from "next/navigation";

export default function BusinessDaysPage() {
  redirect("/dashboard/statistics?tab=close");
}
