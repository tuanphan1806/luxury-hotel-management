export default function DashboardRouteLoading() {
  return (
    <div className="mx-auto w-full max-w-[1600px] space-y-6 p-4 sm:p-6 lg:p-8" role="status" aria-live="polite">
      <span className="sr-only">Đang tải dữ liệu vận hành...</span>
      <div className="skeleton-group space-y-6">
        <div className="space-y-3">
          <div className="skeleton-surface h-8 w-56 rounded-lg" />
          <div className="skeleton-surface h-4 w-80 max-w-full rounded" />
        </div>
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          {[0, 1, 2, 3].map((item) => (
            <div key={item} className="skeleton-surface h-28 rounded-xl border border-[#0F2A43]/8" />
          ))}
        </div>
        <div className="skeleton-surface h-20 rounded-xl border border-[#0F2A43]/8" />
        <div className="skeleton-surface h-[24rem] rounded-xl border border-[#0F2A43]/8" />
      </div>
    </div>
  );
}
