export default function MainRouteLoading() {
  return (
    <div className="min-h-[70vh] bg-[#F1F0EA] px-4 pb-20 pt-28 sm:px-6 lg:px-8" role="status" aria-live="polite">
      <div className="skeleton-group mx-auto max-w-[1400px]">
        <span className="sr-only">Đang mở trang...</span>
        <div className="skeleton-surface h-[34vh] min-h-64 rounded-[1.5rem]" />
        <div className="mt-10 grid gap-6 lg:grid-cols-[1.35fr_0.65fr]">
          <div className="space-y-4">
            <div className="skeleton-surface h-8 w-2/5 rounded-lg" />
            <div className="skeleton-surface h-4 w-full rounded" />
            <div className="skeleton-surface h-4 w-4/5 rounded" />
          </div>
          <div className="skeleton-surface h-40 rounded-[1.25rem]" />
        </div>
      </div>
    </div>
  );
}
