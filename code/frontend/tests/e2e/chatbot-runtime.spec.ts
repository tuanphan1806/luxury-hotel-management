import { expect, test } from '@playwright/test';

const CHAT_STORAGE_KEY = 'luxury-hotel:chat-session:v2';

test.describe('chatbot customer journey', () => {
  test('keeps conversational context and hands a validated selection to the booking page', async ({ page }) => {
    const requests: Array<Record<string, unknown>> = [];
    await page.route('**/backend_proxy/api/add-on-services**', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: [] }),
    }));
    await page.route('**/backend_proxy/api/user/me', (route) => route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ data: null }),
    }));
    await page.route('**/backend_proxy/api/chat', async (route) => {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      requests.push(body);
      const response = requests.length === 1
        ? {
            answer: 'Bạn muốn ở thời gian nào và chọn hạng phòng nào?',
            action: 'CONTINUE_RESERVATION',
            payload: {
              adults: 2,
              children: 0,
              context: 'Đặt phòng cho 2 người',
            },
          }
        : {
            answer: 'Tôi đã kiểm tra dữ liệu. Bạn có thể xem giá chính xác trước khi xác nhận.',
            action: 'CREATE_RESERVATION_CONFIRM',
            payload: {
              checkIn: '2099-08-20T14:00:00',
              checkOut: '2099-08-21T10:00:00',
              adults: 2,
              children: 0,
              guestCount: 2,
              context: 'Đặt 1 phòng Deluxe cho 2 người',
              roomTypes: [{ roomTypeId: 2, quantity: 1 }],
            },
          };
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(response),
      });
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Mở chat hỗ trợ' }).click();
    const input = page.getByRole('textbox', { name: 'Câu hỏi cho trợ lý khách sạn' });

    await input.fill('Tôi cần chỗ ở cho 2 người');
    await page.getByRole('button', { name: 'Gửi tin nhắn' }).click();
    await expect(page.getByText('Bạn muốn ở thời gian nào và chọn hạng phòng nào?')).toBeVisible();

    await input.fill('1 phòng Deluxe từ 20/08/2099 14:00 đến 21/08/2099 10:00');
    await page.getByRole('button', { name: 'Gửi tin nhắn' }).click();
    await expect(page.getByRole('button', { name: 'Xem giá & tiếp tục' })).toBeVisible();

    expect(requests).toHaveLength(2);
    expect(requests[1]?.history).toEqual(expect.arrayContaining([
      expect.objectContaining({ role: 'user', content: 'Tôi cần chỗ ở cho 2 người' }),
      expect.objectContaining({ role: 'assistant', content: 'Bạn muốn ở thời gian nào và chọn hạng phòng nào?' }),
    ]));
    expect(requests[1]?.bookingState).toEqual(expect.objectContaining({ adults: 2, children: 0 }));

    await page.getByRole('button', { name: 'Xem giá & tiếp tục' }).click();
    await expect(page).toHaveURL(/\/booking\?.*roomTypes=2%3A1.*source=chatbot/);
  });

  test('redacts sensitive browser persistence and lets the guest clear the conversation', async ({ page }) => {
    await page.route('**/backend_proxy/api/chat', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ answer: 'Tôi đã nhận câu hỏi nhưng sẽ không lưu thông tin nhạy cảm trong trình duyệt.' }),
      });
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Mở chat hỗ trợ' }).click();
    const input = page.getByRole('textbox', { name: 'Câu hỏi cho trợ lý khách sạn' });
    const privateMessage = 'Email guest@example.com, điện thoại 0901234567, CCCD: 012345678901';

    await input.fill(privateMessage);
    await page.getByRole('button', { name: 'Gửi tin nhắn' }).click();
    await expect(page.getByText('Tôi đã nhận câu hỏi nhưng sẽ không lưu thông tin nhạy cảm trong trình duyệt.')).toBeVisible();

    await expect.poll(async () => page.evaluate((key) => sessionStorage.getItem(key), CHAT_STORAGE_KEY))
      .not.toContain('guest@example.com');
    const persisted = await page.evaluate((key) => sessionStorage.getItem(key) ?? '', CHAT_STORAGE_KEY);
    expect(persisted).not.toContain('0901234567');
    expect(persisted).not.toContain('012345678901');
    expect(persisted).toContain('[email]');

    await page.getByRole('button', { name: 'Xóa cuộc trò chuyện' }).click();
    await expect(page.getByText(privateMessage)).toHaveCount(0);
    await expect(page.getByText('Tôi đã nhận câu hỏi nhưng sẽ không lưu thông tin nhạy cảm trong trình duyệt.')).toHaveCount(0);
    await expect(page.getByText(/Xin chào! Tôi là trợ lý AI của Luxury Hotel/)).toBeVisible();
    await expect.poll(async () => page.evaluate((key) => sessionStorage.getItem(key) ?? '', CHAT_STORAGE_KEY))
      .not.toContain('[email]');
  });

  test('does not append or unlock a stale request after the conversation is cleared', async ({ page }) => {
    let requestCount = 0;
    let markFirstRequestStarted: (() => void) | undefined;
    const firstRequestStarted = new Promise<void>((resolve) => {
      markFirstRequestStarted = resolve;
    });

    await page.route('**/backend_proxy/api/chat', async (route) => {
      requestCount += 1;
      if (requestCount === 1) {
        markFirstRequestStarted?.();
        await new Promise((resolve) => setTimeout(resolve, 700));
        try {
          await route.fulfill({
            status: 200,
            contentType: 'application/json',
            body: JSON.stringify({ answer: 'Phản hồi cũ không được xuất hiện.' }),
          });
        } catch {
          // The browser is expected to abort this request when the guest clears
          // the conversation. Playwright may reject fulfilling an aborted route.
        }
        return;
      }

      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ answer: 'Phản hồi mới thuộc phiên hiện tại.' }),
      });
    });

    await page.goto('/');
    await page.getByRole('button', { name: 'Mở chat hỗ trợ' }).click();
    const input = page.getByRole('textbox', { name: 'Câu hỏi cho trợ lý khách sạn' });

    await input.fill('Câu hỏi của phiên cũ');
    await page.getByRole('button', { name: 'Gửi tin nhắn' }).click();
    await firstRequestStarted;
    await page.getByRole('button', { name: 'Xóa cuộc trò chuyện' }).click();

    await input.fill('Câu hỏi của phiên mới');
    await page.getByRole('button', { name: 'Gửi tin nhắn' }).click();
    await expect(page.getByText('Phản hồi mới thuộc phiên hiện tại.')).toBeVisible();
    await page.waitForTimeout(900);

    await expect(page.getByText('Câu hỏi của phiên cũ')).toHaveCount(0);
    await expect(page.getByText('Phản hồi cũ không được xuất hiện.')).toHaveCount(0);
    await expect(page.getByText('Câu hỏi của phiên mới')).toBeVisible();
    await expect(input).toBeEnabled();
  });
});
