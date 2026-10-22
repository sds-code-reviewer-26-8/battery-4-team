import { test, expect } from './fixtures';
import {
  EVEN, type RouteType,
  login, openNewOrder, runSimulation, pickOption, readStats, readDetailsTable, meta, historyRows,
  apiMaster, orderCount, num,
} from './helpers';

/** 주문 추가 화면에서 시뮬레이션 → (선택) → 확정까지 하고 새 주문 번호를 돌려준다 */
async function confirmViaUi(page: import('@playwright/test').Page, type: RouteType, memo = ''): Promise<number> {
  await openNewOrder(page);
  await runSimulation(page);
  await pickOption(page, type);
  if (memo) await page.locator('#memo').fill(memo);
  await page.locator('#confirmBtn').click();
  await expect(page).toHaveURL(/\/orders\/\d+$/);
  return Number(page.url().match(/\/orders\/(\d+)$/)![1]);
}

test.describe('4. 주문 확정', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('E2E-015 확정하면 상세로 이동하고 메모가 반영된다', async ({ page }) => {
    // 1: 메모 없이
    const id = await confirmViaUi(page, 'COST');
    await expect(page.getByRole('heading', { level: 1 })).toContainText(`#${id}`);
    await expect(page.locator('h1 .badge')).toHaveText('확정');
    await expect(meta(page, '경로')).toHaveText('총비용 최소');
    await expect(meta(page, '메모')).toHaveText('-');
    await expect(meta(page, '작성자')).toHaveText('admin');
    const history = await historyRows(page);
    expect(history).toHaveLength(1);
    expect(history[0].slice(1)).toEqual(['-', '확정', 'admin']);

    // 2: 메모를 적고
    const id2 = await confirmViaUi(page, 'COST', '긴급 주문');
    expect(id2).toBeGreaterThan(id);
    await expect(meta(page, '메모')).toHaveText('긴급 주문');
  });

  test('E2E-016 확정한 주문은 선택한 안과 화면에서 본 계산 그대로 저장된다', async ({ page }) => {
    await openNewOrder(page);
    await runSimulation(page);
    await pickOption(page, 'FAST');
    const stats = await readStats(page);
    const visits = await readDetailsTable(page, 0);
    const ships = await readDetailsTable(page, 1);

    await page.locator('#confirmBtn').click();
    await expect(page).toHaveURL(/\/orders\/\d+$/);
    const id = Number(page.url().match(/\/orders\/(\d+)$/)![1]);

    // 3: 상세가 시뮬레이션 때와 같다
    await expect(meta(page, '경로')).toHaveText('가장 빠르게');
    expect(await readStats(page)).toEqual(stats);
    expect(await readDetailsTable(page, 0)).toEqual(visits);
    expect(await readDetailsTable(page, 1)).toEqual(ships);

    // 4: 목록의 같은 행(경로·소요 일수·총 비용)과 같다
    await page.goto('/orders');
    const row = page.locator('table.grid tbody tr', { has: page.getByRole('link', { name: `#${id}` }) });
    await expect(row).toBeVisible(); // 화면이 목록을 그릴 때까지
    const cells = await row.locator('td').allInnerTexts();
    expect(cells[3].trim()).toBe('가장 빠르게');
    expect(num(cells[4])).toBe(num(stats['소요 일수']));
    expect(cells[5].trim()).toBe(stats['총 비용'].replace('억원', ''));

    // 5: 다른 시뮬레이션에서 '가까운 곳부터'
    await openNewOrder(page);
    await runSimulation(page);
    await pickOption(page, 'NEAR');
    const nearStats = await readStats(page);
    await page.locator('#confirmBtn').click();
    await expect(page).toHaveURL(/\/orders\/\d+$/);
    await expect(meta(page, '경로')).toHaveText('가까운 곳부터');
    expect(await readStats(page)).toEqual(nearStats);
  });

  test('E2E-017 API 로 1,001자 메모·없는 경로 종류를 보내면 이유와 함께 400 이다', async ({ page }) => {
    const before = await orderCount(page);
    const { version } = await apiMaster(page);

    const longMemo = await page.request.post('/api/orders', {
      data: { orders: EVEN, routeType: 'COST', masterVersion: version, memo: 'a'.repeat(1001) },
    });
    expect(longMemo.status()).toBe(400);
    expect((await longMemo.json()).error).toBe('메모는 1000자 이하로 입력하세요');

    const badRoute = await page.request.post('/api/orders', {
      data: { orders: EVEN, routeType: 'XXX', masterVersion: version, memo: '' },
    });
    expect(badRoute.status()).toBe(400);
    expect((await badRoute.json()).error).toBe('선택한 경로를 찾을 수 없습니다');

    expect(await orderCount(page)).toBe(before);
  });

  test('E2E-018 시뮬레이션 뒤 기준 정보가 바뀌면 확정이 거부된다', async ({ page, restoreMaster }) => {
    const before = await orderCount(page);

    // 탭 A: 시뮬레이션만 해 둔다
    await openNewOrder(page);
    await runSimulation(page);

    // 탭 B(같은 세션): 마스터에서 배 척수를 3 → 2 로 저장
    const tabB = await page.context().newPage();
    await tabB.goto('/master');
    await tabB.locator('input[name=shipCount]').fill('2');
    await tabB.getByRole('button', { name: '저장' }).click();
    await expect(tabB.locator('.alert.ok')).toHaveText('마스터 데이터를 저장했습니다');
    await tabB.close();

    // 탭 A: 확정하면 거부된다
    await page.locator('#confirmBtn').click();
    await expect(page.locator('#confirmError')).toContainText('시뮬레이션 이후 마스터 데이터가 바뀌었습니다. 다시 시뮬레이션해 주세요');
    await expect(page).toHaveURL(/\/orders\/new$/);
    expect(await orderCount(page)).toBe(before);

    // 다시 시뮬레이션하면 새 기준으로 확정된다
    await openNewOrder(page);
    await runSimulation(page);
    await page.locator('#confirmBtn').click();
    await expect(page).toHaveURL(/\/orders\/\d+$/);
    await expect(page.locator('#details')).toContainText('배 2척');
  });

  test('E2E-019 API 로 낡은 마스터 버전을 보내면 409 로 거부된다', async ({ page }) => {
    const before = await orderCount(page);
    const res = await page.request.post('/api/orders', {
      data: { orders: EVEN, routeType: 'COST', masterVersion: 0, memo: '' },
    });
    expect(res.status()).toBe(409);
    const body = await res.json();
    expect(body.conflict).toBe(true);
    expect(body.error).toContain('시뮬레이션 이후 마스터 데이터가 바뀌었습니다');
    expect(await orderCount(page)).toBe(before);
  });

  test('E2E-020 시뮬레이션만으로는 주문이 생기지 않는다', async ({ page }) => {
    const before = await orderCount(page);
    await openNewOrder(page);
    await runSimulation(page);
    expect(await orderCount(page)).toBe(before);
  });
});
