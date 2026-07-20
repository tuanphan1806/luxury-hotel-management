const guestTokenKey = (reservationId: string | number) => `guest_token_${reservationId}`;
const legacyGuestTokenKey = (reservationId: string | number) => `guest_reservation_${reservationId}_token`;

export const saveGuestReservationToken = (reservationId: string | number, token: string) => {
  sessionStorage.setItem(guestTokenKey(reservationId), token);
  localStorage.removeItem(guestTokenKey(reservationId));
  localStorage.removeItem(legacyGuestTokenKey(reservationId));
};

export const getGuestReservationToken = (reservationId: string | number) => {
  const current = sessionStorage.getItem(guestTokenKey(reservationId));
  if (current) return current;

  // Di chuyển token cũ sang sessionStorage một lần để giảm thời gian lưu capability token.
  const legacy = localStorage.getItem(guestTokenKey(reservationId))
    || localStorage.getItem(legacyGuestTokenKey(reservationId));
  if (legacy) {
    sessionStorage.setItem(guestTokenKey(reservationId), legacy);
    localStorage.removeItem(guestTokenKey(reservationId));
    localStorage.removeItem(legacyGuestTokenKey(reservationId));
  }
  return legacy;
};

export const clearGuestReservationToken = (reservationId: string | number) => {
  localStorage.removeItem(guestTokenKey(reservationId));
  localStorage.removeItem(legacyGuestTokenKey(reservationId));
  sessionStorage.removeItem(guestTokenKey(reservationId));
  sessionStorage.removeItem(legacyGuestTokenKey(reservationId));
};
