import { randomUUID } from 'node:crypto';
import { expect, type APIRequestContext } from '@playwright/test';
import { requireIsolation } from './require-isolation';

export async function openStaffShift(request: APIRequestContext, adminToken: string, staffToken: string) {
  requireIsolation();
  const api = process.env.E2E_QA_API!;
  const staffHeaders = { Authorization: `Bearer ${staffToken}` };
  const profile = await request.get(`${api}/api/user/me`, { headers: staffHeaders });
  expect(profile.status()).toBe(200);
  const profileBody = await profile.json();
  const employeeId = (profileBody.data ?? profileBody).id;
  expect(employeeId).toBeTruthy();
  const hotelTime = (offset: number) => new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).format(new Date(Date.now() + offset));
  const start = hotelTime(0);
  const workDate = start.slice(0, 10);
  const startTime = start.slice(11);
  const endTime = hotelTime(2 * 60 * 60 * 1000).slice(11);
  const post = async (path: string, data: object, token = adminToken) => {
    const response = await request.post(`${api}${path}`, {
      headers: { Authorization: `Bearer ${token}`, 'Idempotency-Key': randomUUID() }, data,
    });
    expect(response.status(), await response.text()).toBe(200);
    return (await response.json()).data;
  };
  const template = await post('/api/work-schedules/templates', {
    code: `QA_${randomUUID().slice(0, 8)}`, name: 'Isolated cashier QA',
    startTime, endTime, checkInEarlyMinutes: 30, lateToleranceMinutes: 30,
    color: '#123456', sortOrder: 99, active: true,
  });
  await post('/api/work-schedules/daily-shifts', {
    shiftTemplateId: template.id, workDate, shiftName: 'Isolated cashier QA',
    startTime, endTime, requiredStaff: 1, registrationOpen: false,
    assignmentPolicy: 'ADMIN_ONLY', checkInEarlyMinutes: 30, lateToleranceMinutes: 30,
    color: '#123456', note: 'Synthetic QA shift',
  });
  const assignment = await post('/api/work-schedules/assignments', {
    employeeId, shiftTemplateId: template.id, workDate, note: 'Synthetic QA assignment',
  });
  await post(`/api/work-schedules/assignments/${assignment.id}/check-in`, { note: 'QA attendance' }, staffToken);
  const current = await request.get(`${api}/api/accounting/cashier-shifts/current`, { headers: staffHeaders });
  expect(current.status()).toBe(200);
  const cashierShift = (await current.json()).data;
  expect(cashierShift.status).toBe('OPEN');
  return async () => {
    await post(`/api/work-schedules/assignments/${assignment.id}/check-out`, { note: 'QA verification completed' }, staffToken);
    const closed = await request.get(`${api}/api/accounting/cashier-shifts/current`, { headers: staffHeaders });
    expect(closed.status()).toBe(200);
    expect((await closed.json()).data ?? null).toBeNull();
    const shift = await request.get(`${api}/api/accounting/cashier-shifts/${cashierShift.id}`, { headers: staffHeaders });
    expect(shift.status()).toBe(200);
    expect((await shift.json()).data.status).toBe('CLOSED');
  };
}
