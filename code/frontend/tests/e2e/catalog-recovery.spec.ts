import { expect, test } from '@playwright/test';

test('recovers a temporary catalog outage and clears the loading notice', async ({ page }) => {
  let requests = 0;
  await page.route('**/catalog_proxy/room-types', async (route) => {
    requests += 1;
    if (requests === 1) {
      await new Promise((resolve) => setTimeout(resolve, 1_000));
      return route.fulfill({ status: 503, body: '{}' });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [{
      id: 1, typeName: 'Phòng kiểm tra kết nối', typeNameEn: 'Connection test room',
      maxGuests: 2, description: 'Phòng mẫu kiểm thử', imageUrl: '/media/heroes/g-9.webp',
      pricingAvailable: true, overnightPrice: 300000, dailyPrice: 400000,
    }] }) });
  });
  await page.goto('/rooms');
  await expect(page.getByRole('status').filter({ hasText: 'Dữ liệu đang tải chậm' })).toBeVisible({ timeout: 10_000 });
  await expect(page.getByRole('heading', { name: 'Phòng kiểm tra kết nối' })).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText('Dữ liệu đang tải chậm.', { exact: false })).toHaveCount(0);
  expect(requests).toBe(2);
});

test('distinguishes a failed catalog from no rooms and permits a manual reload', async ({ page }, testInfo) => {
  let requests = 0;
  await page.route('**/catalog_proxy/room-types', (route) => {
    requests += 1;
    return route.fulfill({ status: 503, body: '{}' });
  });
  await page.goto('/rooms');
  await expect(page.getByRole('heading', { name: 'Chưa tải được danh sách phòng' })).toBeVisible({ timeout: 20_000 });
  await expect(page.getByRole('button', { name: 'Tải lại trang', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Chưa có loại phòng', exact: true })).toHaveCount(0);
  expect(requests).toBe(3);
  await page.screenshot({ path: testInfo.outputPath('catalog-unavailable.png'), fullPage: false });
  await page.route('**/catalog_proxy/room-types', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }),
  }));
  await page.getByRole('button', { name: 'Tải lại trang', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Chưa có loại phòng', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tải lại trang', exact: true })).toHaveCount(0);
});
