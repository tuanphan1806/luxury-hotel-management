type IdleWindow = Window & {
  requestIdleCallback?: (callback: () => void, options?: { timeout: number }) => number;
  cancelIdleCallback?: (id: number) => void;
};

type NavigatorWithConnection = Navigator & {
  connection?: {
    saveData?: boolean;
    effectiveType?: string;
  };
};

export const shouldConserveData = () => {
  if (typeof navigator === "undefined") return false;
  const connection = (navigator as NavigatorWithConnection).connection;
  return Boolean(connection?.saveData || connection?.effectiveType?.includes("2g"));
};

export const scheduleIdleTask = (callback: () => void, timeout = 2_000) => {
  const idleWindow = window as IdleWindow;
  if (idleWindow.requestIdleCallback) {
    const id = idleWindow.requestIdleCallback(callback, { timeout });
    return () => idleWindow.cancelIdleCallback?.(id);
  }
  const id = window.setTimeout(callback, Math.min(timeout, 1_000));
  return () => window.clearTimeout(id);
};
