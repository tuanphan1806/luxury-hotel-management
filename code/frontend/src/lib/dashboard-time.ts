export type DashboardTimeScope = "ALL" | "TODAY" | "WEEK" | "MONTH";
export type DashboardTimeGrouping = "DAY" | "WEEK" | "MONTH";

type DateInput = string | number | Date | null | undefined;

export interface DashboardCalendarGroup<T> {
  key: string;
  start: Date | null;
  end: Date | null;
  items: T[];
}

const toValidDate = (value: DateInput) => {
  if (value === null || value === undefined || value === "") return null;
  const date = value instanceof Date ? new Date(value.getTime()) : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
};

const startOfDay = (value: Date) => {
  const date = new Date(value);
  date.setHours(0, 0, 0, 0);
  return date;
};

const startOfWeek = (value: Date) => {
  const date = startOfDay(value);
  const day = date.getDay();
  date.setDate(date.getDate() - (day === 0 ? 6 : day - 1));
  return date;
};

const startOfMonth = (value: Date) => {
  const date = startOfDay(value);
  date.setDate(1);
  return date;
};

const addDays = (value: Date, amount: number) => {
  const date = new Date(value);
  date.setDate(date.getDate() + amount);
  return date;
};

const addMonths = (value: Date, amount: number) => {
  const date = new Date(value);
  date.setMonth(date.getMonth() + amount);
  return date;
};

const getScopeRange = (scope: Exclude<DashboardTimeScope, "ALL">, reference: Date) => {
  if (scope === "TODAY") {
    const start = startOfDay(reference);
    return { start, end: addDays(start, 1) };
  }
  if (scope === "WEEK") {
    const start = startOfWeek(reference);
    return { start, end: addDays(start, 7) };
  }
  const start = startOfMonth(reference);
  return { start, end: addMonths(start, 1) };
};

export const matchesPointTimeScope = (
  value: DateInput,
  scope: DashboardTimeScope,
  reference = new Date(),
) => {
  if (scope === "ALL") return true;
  const date = toValidDate(value);
  if (!date) return false;
  const range = getScopeRange(scope, reference);
  return date >= range.start && date < range.end;
};

export const matchesIntervalTimeScope = (
  startValue: DateInput,
  endValue: DateInput,
  scope: DashboardTimeScope,
  reference = new Date(),
) => {
  if (scope === "ALL") return true;
  const start = toValidDate(startValue);
  const end = toValidDate(endValue);
  if (!start) return false;
  if (!end || end <= start) return matchesPointTimeScope(start, scope, reference);
  const range = getScopeRange(scope, reference);
  return start < range.end && end > range.start;
};

const padDatePart = (value: number) => String(value).padStart(2, "0");

const calendarKey = (prefix: string, value: Date) => (
  `${prefix}-${value.getFullYear()}-${padDatePart(value.getMonth() + 1)}-${padDatePart(value.getDate())}`
);

const getGroupingRange = (value: Date, grouping: DashboardTimeGrouping) => {
  if (grouping === "DAY") {
    const start = startOfDay(value);
    return { key: calendarKey("day", start), start, end: addDays(start, 1) };
  }
  if (grouping === "WEEK") {
    const start = startOfWeek(value);
    return { key: calendarKey("week", start), start, end: addDays(start, 7) };
  }
  const start = startOfMonth(value);
  return { key: calendarKey("month", start), start, end: addMonths(start, 1) };
};

export const groupByCalendarTime = <T>(
  items: T[],
  getDate: (item: T) => DateInput,
  grouping: DashboardTimeGrouping,
  reference = new Date(),
): DashboardCalendarGroup<T>[] => {
  const groups = new Map<string, DashboardCalendarGroup<T>>();

  items.forEach((item) => {
    const date = toValidDate(getDate(item));
    const range = date
      ? getGroupingRange(date, grouping)
      : { key: "unknown-date", start: null, end: null };
    const existing = groups.get(range.key);
    if (existing) {
      existing.items.push(item);
      return;
    }
    groups.set(range.key, { ...range, items: [item] });
  });

  const groupRank = (group: DashboardCalendarGroup<T>) => {
    if (!group.start || !group.end) return 3;
    if (group.start <= reference && reference < group.end) return 0;
    if (group.start > reference) return 1;
    return 2;
  };

  return Array.from(groups.values()).sort((left, right) => {
    const rankDifference = groupRank(left) - groupRank(right);
    if (rankDifference !== 0) return rankDifference;
    if (!left.start || !right.start) return 0;
    if (groupRank(left) === 1) return left.start.getTime() - right.start.getTime();
    if (groupRank(left) === 2) return right.start.getTime() - left.start.getTime();
    return left.start.getTime() - right.start.getTime();
  });
};

const capitalizeFirst = (value: string) => value.charAt(0).toLocaleUpperCase() + value.slice(1);
const formatDayMonth = (value: Date) => `${padDatePart(value.getDate())}/${padDatePart(value.getMonth() + 1)}`;
const formatFullDate = (value: Date) => `${formatDayMonth(value)}/${value.getFullYear()}`;

export const formatDashboardTimeGroupLabel = <T>(
  group: DashboardCalendarGroup<T>,
  grouping: DashboardTimeGrouping,
  localeTag: string,
  weekLabel: string,
  unknownLabel: string,
) => {
  if (!group.start || !group.end) return unknownLabel;
  if (grouping === "DAY") {
    return capitalizeFirst(group.start.toLocaleDateString(localeTag, {
      weekday: "long",
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    }));
  }
  if (grouping === "WEEK") {
    const lastDay = new Date(group.end.getTime() - 1);
    return `${weekLabel} ${formatDayMonth(group.start)} – ${formatFullDate(lastDay)}`;
  }
  return capitalizeFirst(group.start.toLocaleDateString(localeTag, { month: "long", year: "numeric" }));
};
