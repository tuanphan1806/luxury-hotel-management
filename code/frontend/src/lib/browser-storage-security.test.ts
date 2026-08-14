import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  clearGuestReservationToken,
  getGuestReservationToken,
  saveGuestReservationToken,
} from './guest-reservation-token';
import {
  clearIdempotencyKey,
  getOrCreateIdempotencyKey,
} from './idempotency';

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>();

  get length() {
    return this.values.size;
  }

  clear() {
    this.values.clear();
  }

  getItem(key: string) {
    return this.values.get(key) ?? null;
  }

  key(index: number) {
    return Array.from(this.values.keys())[index] ?? null;
  }

  removeItem(key: string) {
    this.values.delete(key);
  }

  setItem(key: string, value: string) {
    this.values.set(key, String(value));
  }
}

describe('browser capability storage', () => {
  let localStorage: MemoryStorage;
  let sessionStorage: MemoryStorage;

  beforeEach(() => {
    localStorage = new MemoryStorage();
    sessionStorage = new MemoryStorage();
    vi.stubGlobal('localStorage', localStorage);
    vi.stubGlobal('sessionStorage', sessionStorage);
    vi.stubGlobal('window', { localStorage, sessionStorage });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('keeps new guest reservation tokens in the current tab only', () => {
    localStorage.setItem('guest_token_42', 'stale-current');
    localStorage.setItem('guest_reservation_42_token', 'stale-legacy');

    saveGuestReservationToken(42, 'fresh-token');

    expect(sessionStorage.getItem('guest_token_42')).toBe('fresh-token');
    expect(localStorage.getItem('guest_token_42')).toBeNull();
    expect(localStorage.getItem('guest_reservation_42_token')).toBeNull();
  });

  it('migrates a legacy persistent guest token into session storage once', () => {
    localStorage.setItem('guest_reservation_7_token', 'legacy-token');

    expect(getGuestReservationToken(7)).toBe('legacy-token');
    expect(sessionStorage.getItem('guest_token_7')).toBe('legacy-token');
    expect(localStorage.getItem('guest_reservation_7_token')).toBeNull();

    clearGuestReservationToken(7);
    expect(getGuestReservationToken(7)).toBeNull();
  });

  it('reuses an idempotency key only until the operation is cleared', () => {
    const randomUUID = vi.fn()
      .mockReturnValueOnce('operation-key-1')
      .mockReturnValueOnce('operation-key-2');
    vi.stubGlobal('crypto', { randomUUID });

    expect(getOrCreateIdempotencyKey('reservation:create')).toBe('operation-key-1');
    expect(getOrCreateIdempotencyKey('reservation:create')).toBe('operation-key-1');
    expect(randomUUID).toHaveBeenCalledTimes(1);

    clearIdempotencyKey('reservation:create');
    expect(getOrCreateIdempotencyKey('reservation:create')).toBe('operation-key-2');
  });
});
