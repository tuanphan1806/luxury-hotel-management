import { expect, test } from '@playwright/test';

const room = {
  id: 91, typeName: 'Phòng gia đình', maxGuests: 6, description: 'Ban công',
  facilities: [{ facilityName: 'Hồ bơi' }], imageUrl: '/media/heroes/g-9.webp',
  pricingAvailable: true, dailyPrice: 400000, overnightPrice: 300000,
};
const booking = {
  id: 98765, reservationCode: 'QA-LOOKUP-98765', status: 'CONFIRMED', totalAmount: 400000,
  checkIn: '2026-12-01T14:00:00', checkOut: '2026-12-02T12:00:00',
};

test('distinguishes missing inventory from sold-out stays and offers only rooms with stock', async ({ page }) => {
  const candidate = { roomTypeId: 91, roomTypeName: 'Phòng QA', maxGuestsPerRoom: 2 };
  let results: object[] = [candidate];
  await page.route('**/backend_proxy/api/reservations/availability?**', route => route.fulfill({ json: { success: true, data: results } }));
  await page.goto('/reservation');
  const list = page.locator('#availability-results');
  await expect(list.getByText('Chưa kiểm tra được phòng trống cho thời gian bạn chọn.')).toBeVisible();
  results = [{ ...candidate, availableRooms: 0 }];
  await list.getByRole('button', { name: 'Thử lại' }).click();
  await expect(list.getByRole('heading', { name: 'Chưa có phòng phù hợp' })).toBeVisible();
  results.push({ ...candidate, roomTypeId: 92, roomTypeName: 'Phòng còn trống', availableRooms: 2 });
  await page.reload();
  await expect(list.getByRole('heading', { name: 'Phòng còn trống' })).toBeVisible();
  await expect(list.getByRole('heading', { name: 'Phòng QA' })).toHaveCount(0);
});

test('finds Vietnamese room names and amenities and restores a filtered favorite', async ({ page }) => {
  await page.route('**/catalog_proxy/room-types', route => route.fulfill({ json: { success: true, data: [room] } }));
  await page.goto('/rooms');
  const title = page.getByRole('heading', { name: room.typeName, exact: true });
  await expect(page.getByRole('button', { name: `Thêm ${room.typeName} vào yêu thích` })).toBeVisible();
  const search = page.getByRole('searchbox', { name: 'Tên phòng hoặc tiện nghi' });
  for (const term of ['hồ bơi', ' HO   BOI ', 'gia dinh']) {
    await search.fill(term);
    await expect(title).toBeVisible();
  }
  await page.getByRole('button', { name: `Thêm ${room.typeName} vào yêu thích` }).click();
  await page.goto('/rooms?favorites=1');
  await expect(page.getByRole('button', { name: `Bỏ ${room.typeName} khỏi yêu thích` })).toBeVisible();
  await search.fill('unmatched');
  await expect(page.getByRole('heading', { name: 'Thử nới điều kiện tìm kiếm' })).toBeVisible();
  await page.getByRole('button', { name: 'Đặt lại bộ lọc', exact: true }).click();
  await expect(title).toBeVisible();
});

for (const separator of ['?', '#']) {
  test(`opens a ${separator === '?' ? 'legacy' : 'current'} email link and restores only its booking on reload`, async ({ page }) => {
    await page.route('**/api/reservations/lookup', route => {
      expect(route.request().headers()['x-guest-token']).toBe('qa-lookup-only');
      return route.fulfill({ json: { success: true, data: booking } });
    });
    await page.goto(`/booking/lookup${separator}token=qa-lookup-only`);
    const title = page.getByRole('heading', { name: booking.reservationCode });
    await expect(title).toBeVisible();
    await expect(page).toHaveURL(/\/booking\/lookup$/);
    await page.reload();
    await expect(title).toBeVisible();
    await expect(page).toHaveURL(/\/booking\/lookup$/);
  });
}

test('retries a temporary lookup failure even with browser storage disabled', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'sessionStorage', { get() { throw new DOMException('Blocked', 'SecurityError'); } });
  });
  let restored = false;
  await page.route('**/api/reservations/lookup', route => restored
    ? route.fulfill({ json: { success: true, data: booking } })
    : route.fulfill({ status: 503, json: { message: 'Dịch vụ tạm gián đoạn' } }));
  await page.goto('/booking/lookup#token=qa-lookup-only');
  await expect(page.getByText('Dịch vụ tạm gián đoạn')).toBeVisible();
  restored = true;
  await page.getByRole('button', { name: 'Thử lại', exact: true }).click();
  await expect(page.getByRole('heading', { name: booking.reservationCode })).toBeVisible();
});

test('handles an invalid guest link and explains where guests can find their booking', async ({ page }) => {
  let reads = 0;
  await page.route('**/api/reservations/lookup', route => {
    reads++;
    return route.fulfill({ status: 404, json: { message: 'Liên kết không hợp lệ' } });
  });
  await page.goto('/booking/lookup#token=qa-invalid');
  await expect(page.getByText('Liên kết không hợp lệ')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Thử lại', exact: true })).toHaveCount(0);
  await page.goto('/booking/lookup');
  await expect(page).toHaveURL(/\/my-bookings$/);
  await expect(page.getByText(/Nếu đã đặt phòng không cần tài khoản/)).toBeVisible();
  expect(reads).toBe(1);
});
