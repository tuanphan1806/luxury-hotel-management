export const MAX_STAY_DAYS = 365;

const DAY_MS = 24 * 60 * 60 * 1000;

export const isStayWithinMaximum = (
  checkIn: string | Date,
  checkOut: string | Date,
  maximumDays = MAX_STAY_DAYS,
) => {
  const start = checkIn instanceof Date ? checkIn : new Date(checkIn);
  const end = checkOut instanceof Date ? checkOut : new Date(checkOut);
  if (
    Number.isNaN(start.getTime())
    || Number.isNaN(end.getTime())
    || end <= start
  ) {
    return false;
  }
  return end.getTime() - start.getTime() <= maximumDays * DAY_MS;
};
