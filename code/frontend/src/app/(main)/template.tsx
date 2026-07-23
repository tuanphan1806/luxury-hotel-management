import GuestMotionController from "@/components/guest/GuestMotionController";

export default function MainRouteTemplate({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <div className="guest-route-enter min-w-0">
      <GuestMotionController />
      {children}
    </div>
  );
}
