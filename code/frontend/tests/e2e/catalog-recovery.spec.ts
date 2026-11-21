import { expect, test } from '@playwright/test';

test('recovers a temporary catalog outage and clears the loading notice', async ({ page }) => {
  let requests = 0;
  await page.route('**/actuator/health/readiness', (route) => route.fulfill({ status: 503 }));
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

test('keeps prerendered rooms on failure and distinguishes an unknown catalog from an empty result', async ({ page, browser }, testInfo) => {
  let requests = 0;
  await page.route('**/catalog_proxy/room-types', (route) => {
    requests += 1;
    // Permanent errors fail immediately; the bounded cold-start recovery
    // window is exercised with fake timers in public-catalog.test.ts.
    return route.fulfill({ status: 400, body: '{}' });
  });
  await page.goto('/rooms');
  await expect(page.getByRole('button', { name: 'Tải lại trang', exact: true })).toBeVisible();
  const staticHtml = await (await page.request.get('/rooms')).text();
  const initial = await page.evaluate((html) => {
    const document = new DOMParser().parseFromString(html, 'text/html');
    return {
      name: document.querySelector('article h3')?.textContent,
      empty: Array.from(document.querySelectorAll('h3')).some((h) => h.textContent === 'Chưa có loại phòng'),
    };
  }, staticHtml);
  if (initial.name) {
    const beforeHydration = await browser.newPage({ javaScriptEnabled: false });
    try {
      await beforeHydration.goto(page.url());
      const card = beforeHydration.locator('article').filter({ hasText: initial.name }).first();
      await expect(card).toBeVisible();
      await expect(card.getByRole('link', { name: 'Kiểm tra ngày trống' })).toBeVisible();
    } finally { await beforeHydration.close(); }
    await expect(page.getByRole('heading', { name: initial.name, exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Kiểm tra ngày trống' }).first()).toBeVisible();
  } else if (!initial.empty) {
    await expect(page.getByRole('heading', { name: 'Chưa tải được danh sách phòng' })).toBeVisible();
    await expect(page.getByText('hạng phòng phù hợp', { exact: false }).filter({ hasText: /^0 hạng/ })).toHaveCount(0);
  }
  expect(requests).toBe(1);
  await page.screenshot({ path: testInfo.outputPath('catalog-unavailable.png'), fullPage: false });
  await page.route('**/catalog_proxy/room-types', (route) => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify({ data: [] }),
  }));
  await page.getByRole('button', { name: 'Tải lại trang', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Chưa có loại phòng', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Tải lại trang', exact: true })).toHaveCount(0);
});

test('retries the same stay after an outage while optional catalog details are still unavailable', async ({ page }) => {
  const searches: string[] = [];
  await page.route('**/catalog_proxy/room-types', (route) => route.fulfill({
    status: 503, headers: { 'Retry-After': '60' }, body: '{}',
  }));
  await page.route('**/backend_proxy/api/reservations/availability?**', (route) => {
    searches.push(new URL(route.request().url()).search);
    if (searches.length === 1) return route.fulfill({ status: 503, body: '{}' });
    return route.fulfill({ json: { data: [{
      roomTypeId: 9, roomTypeName: 'Phòng trống theo ngày', availableRooms: 2, maxGuestsPerRoom: 4,
      estimatedPricePerRoom: 250000, estimatedPackage: 'HOURLY',
    }] } });
  });
  const day = new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10);
  await page.goto(`/reservation?checkIn=${day}T12:00&checkOut=${day}T14:00&adults=3&children=1&roomTypeId=9`);
  const results = page.locator('#availability-results');
  const retry = results.getByRole('button', { name: 'Thử lại' });
  await expect(retry).toBeVisible();
  await expect(results.locator('.skeleton-surface')).toHaveCount(0);
  await page.clock.install();
  await page.clock.fastForward(5_000);
  await expect(retry).toBeVisible();
  await retry.click();
  await expect(results.getByRole('heading', { name: 'Phòng trống theo ngày' })).toBeVisible();
  await expect(results.getByRole('alert')).toHaveCount(0);
  expect(searches).toHaveLength(2);
  expect(searches[0]).toBe(searches[1]);
  expect(new URLSearchParams(searches[1]).get('checkIn')).toBe(`${day}T12:00:00`);
  await expect(page.getByRole('combobox', { name: 'Người lớn', exact: true })).toHaveValue('3');
  await expect(page.getByRole('combobox', { name: 'Trẻ em', exact: true })).toHaveValue('1');
  await expect(results.getByRole('combobox', { name: 'Số phòng Phòng trống theo ngày', exact: true })).toHaveValue('1');
});

test('a confirmed wake retries an interrupted availability read without another click', async ({ page }) => {
  let ready!: () => void;
  const wake = new Promise<void>((resolve) => { ready = resolve; });
  await page.route('**/actuator/health/readiness', async (route) => {
    await wake;
    await route.fulfill({ json: { status: 'UP' } });
  });
  let attempts = 0;
  await page.route('**/backend_proxy/api/reservations/availability?**', (route) => {
    attempts++;
    return attempts === 1 ? route.fulfill({ status: 503, body: '{}' }) : route.fulfill({ json: { data: [] } });
  });
  await page.goto('/reservation');
  const results = page.locator('#availability-results');
  await expect(results.getByRole('button', { name: 'Thử lại' })).toBeVisible();
  ready();
  await expect(results.getByRole('heading', { name: 'Chưa có phòng phù hợp' })).toBeVisible();
  expect(attempts).toBe(2);
});

test('a swipe beginning on a gallery photo can scroll the page or the photo strip', async ({ page, isMobile, context }) => {
  test.skip(!isMobile, 'Touch gestures are exercised on the mobile Chrome project.');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.route('**/catalog_proxy/galleries', (route) => route.fulfill({ json: { data: [1, 2, 3].map((id) =>
    ({ id, title: `Ảnh ${id}`, imageUrl: '/media/heroes/g-9.webp' })) } }));
  await page.goto('/');
  const strip = page.getByTestId('home-gallery-mobile-scroller');
  await expect(strip.locator('figure')).toHaveCount(3);
  const touch = await context.newCDPSession(page);
  const swipe = async (dx: number, dy: number) => {
    await strip.evaluate((node) => node.scrollIntoView({ block: 'center', behavior: 'instant' }));
    const box = (await strip.boundingBox())!;
    const x = box.x + box.width * 0.65;
    const y = box.y + box.height * 0.65;
    await touch.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    for (let step = 1; step <= 6; step++) {
      await touch.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * step / 6, y: y + dy * step / 6 }] });
    }
    await touch.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  };
  await swipe(-190, 0);
  await expect.poll(() => strip.evaluate((el) => el.scrollLeft)).toBeGreaterThan(50);
  await strip.evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'instant' }));
  const before = await page.evaluate(() => window.scrollY);
  await swipe(0, -150);
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(before + 40);
});
