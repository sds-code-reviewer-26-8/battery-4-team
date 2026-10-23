import { test, expect } from './fixtures';
import { EVEN, login, apiMaster, apiSimulate } from './helpers';

/** 확정 때 고른 필요 납기일이 주문에 저장되고, 목록·상세에 빨간색+글자로 보이는지 본다. */
async function confirmWithDue(page: import('@playwright/test').Page, dueDays: number | null, routeType = 'COST') {
  const master = await apiMaster(page);
  const res = await page.request.post('/api/orders', {
    data: { orders: EVEN, routeType, masterVersion: master.version, memo: '', dueDays },
  });
  expect(res.status(), await res.text()).toBe(200);
  return (await res.json()).id as number;
}

test.describe('필요 납기일 저장과 표시', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('납기를 넘는 주문은 목록·상세에서 빨간색과 납기 초과 글자로 보인다', async ({ page }) => {
    const sim = await apiSimulate(page, EVEN);
    const cost = sim.options.find((o: { type: string }) => o.type === 'COST').totalDays as number;
    const id = await confirmWithDue(page, 5);
    const over = cost - 5;
    expect(over).toBeGreaterThan(0);

    await page.goto('/orders');
    const cell = page.locator(`tr[data-id="${id}"] .late-text`);
    await expect(cell).toContainText(`5일 · 납기 초과 ${over}일`);
    await expect(cell).toHaveCSS('color', 'rgb(179, 38, 30)');

    await page.goto(`/orders/${id}`);
    const d = page.locator('#orderInfo .late-text');
    await expect(d).toContainText(`납기 초과 ${over}일`);
    await expect(d).toHaveCSS('color', 'rgb(179, 38, 30)');
  });

  test('납기 안의 주문은 납기만 보이고 빨간 표시가 없다', async ({ page }) => {
    const id = await confirmWithDue(page, 50);
    await page.goto('/orders');
    await expect(page.locator(`tr[data-id="${id}"]`)).toContainText('50일');
    await expect(page.locator(`tr[data-id="${id}"] .late-text`)).toHaveCount(0);
    await page.goto(`/orders/${id}`);
    await expect(page.locator('#orderInfo')).toContainText('50일');
    await expect(page.locator('#orderInfo .late-text')).toHaveCount(0);
  });

  test('가능한 빨리 주문은 납기 열에 가능한 빨리만 보이고 빨간 표시가 없다', async ({ page }) => {
    const id = await confirmWithDue(page, null);
    await page.goto('/orders');
    await expect(page.locator(`tr[data-id="${id}"]`)).toContainText('가능한 빨리');
    await expect(page.locator(`tr[data-id="${id}"] .late-text`)).toHaveCount(0);
    await page.goto(`/orders/${id}`);
    await expect(page.locator('#orderInfo')).toContainText('가능한 빨리');
    await expect(page.locator('#orderInfo .late-text')).toHaveCount(0);
  });

  test('화면에서 납기를 골라 확정하면 상세에 저장된 납기가 보인다', async ({ page }) => {
    await page.goto('/orders/new');
    await page.locator('#runBtn').click();
    await expect(page.locator('#result')).toBeVisible();
    await page.locator('#dueAsap').uncheck();
    await page.locator('#dueDays').fill('5');
    await page.locator('#confirmBtn').click();
    await page.waitForURL(/\/orders\/\d+$/);
    await expect(page.locator('#orderInfo .late-text')).toContainText('5일 · 납기 초과');
  });

  test('서버는 범위 밖·비정수 납기를 400 으로 거부한다', async ({ page }) => {
    const master = await apiMaster(page);
    for (const bad of [4, 51, 5.5, '10']) {
      const res = await page.request.post('/api/orders', {
        data: { orders: EVEN, routeType: 'COST', masterVersion: master.version, memo: '', dueDays: bad },
      });
      expect(res.status(), String(bad)).toBe(400);
    }
  });
});
