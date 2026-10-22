import { expect, test } from './fixtures';
import {
  apiSimulate, createOrder, login, openNewOrder, readDetailsTable, readMasterForm, readStats, runSimulation, setGauge,
  setTotal,
} from './helpers';

/**
 * 공장 운영비 (PRD 9장 '공장 운영비', INTENT 8.4)
 * - 안마다 생산 원가, 운항비, 운영비, 총 비용이 따로 보이고 공장별 운영 일수·운영비 표가 있다
 * - 가장 빠르게와 총비용 최소가 같은 안이면 카드 아래에 '두 안이 같습니다' 한 줄 안내가 보인다
 * - 확정 주문은 확정 당시 운영비를 그대로 보여 준다
 * - 운영비 도입 전에 확정한 주문은 열리고 운영비는 '없음', 총 비용은 저장값 그대로다
 * - 마스터 하루 운영비는 0 초과 1,000억 원 이하이고 잘못된 값이 있으면 아무것도 저장하지 않는다
 */

const eok = (won: number) => (won / 1e8).toFixed(2);

/** 두 안의 경로가 다른 수량 (배 3척 기본) */
const DIFFERENT = { CHINA: 5000, JAPAN: 2000, VIETNAM: 0, INDONESIA: 8000 };

type FactoryOp = { country: string; days: number; cost: number };

test.describe('공장 운영비', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('안마다 4항목이 보이고 총 비용 = 생산 원가 + 운항비 + 운영비이며 공장별 표가 맨 뒤에 있다', async ({ page }) => {
    await openNewOrder(page);
    await setTotal(page, 15000);
    for (const [c, v] of Object.entries(DIFFERENT)) await setGauge(page, c as 'CHINA', v);
    await runSimulation(page);
    const sim = await apiSimulate(page, DIFFERENT);

    for (const o of sim.options) {
      const card = page.locator(`.option[data-type="${o.type}"]`);
      await expect(card).toContainText(`총 비용 ${eok(o.totalCost)}억원`);
      await expect(card).toContainText(`생산 원가 ${eok(o.productionCost)}억원`);
      await expect(card).toContainText(`운항비 ${eok(o.shippingCost)}억원`);
      await expect(card).toContainText(`운영비 ${eok(o.operatingCost)}억원`);
      expect(o.totalCost).toBe(o.productionCost + o.shippingCost + o.operatingCost);
      // 공장 운영비의 합 = 안의 운영비, 하루 10억 x 운영 일수
      const ops = o.factoryOperations as FactoryOp[];
      expect(ops.reduce((a, f) => a + f.cost, 0)).toBe(o.operatingCost);
      ops.forEach((f) => expect(f.cost).toBe(f.days * 10 * 1e8));
      // 주문 없는 공장(베트남)은 운영 일수와 운영비가 0
      expect(ops.find((f) => f.country === 'VIETNAM')).toMatchObject({ days: 0, cost: 0 });
    }

    // 요약 카드 4항목과 공장별 표(맨 뒤, 표 번호 5)가 선택한 안과 같다
    const cost = sim.options.find((o: { type: string }) => o.type === 'COST');
    const stats = await readStats(page);
    expect(stats['생산 원가']).toBe(`${eok(cost.productionCost)}억원`);
    expect(stats['운항비']).toBe(`${eok(cost.shippingCost)}억원`);
    expect(stats['운영비']).toBe(`${eok(cost.operatingCost)}억원`);
    expect(stats['총 비용']).toBe(`${eok(cost.totalCost)}억원`);
    const rows = await readDetailsTable(page, 5);
    expect(rows).toHaveLength(4);
    (cost.factoryOperations as FactoryOp[]).forEach((f, i) => {
      expect(rows[i][1]).toBe(`${f.days}일`);
      expect(rows[i][2]).toBe(eok(f.cost));
    });
  });

  test('가장 빠르게와 총비용 최소가 같은 안이면 카드 아래에 한 줄 안내가 보이고, 다르면 보이지 않는다', async ({ page }) => {
    // 같은 경우: 한 나라만 주문 (경로가 하나뿐)
    await openNewOrder(page);
    for (const c of ['JAPAN', 'VIETNAM', 'INDONESIA'] as const) await setGauge(page, c, 0);
    await runSimulation(page);
    const same = await apiSimulate(page, { CHINA: 12000 });
    expect(JSON.stringify(same.options[0].ships)).toBe(JSON.stringify(same.options[1].ships));
    await expect(page.locator('#sameNote')).toBeVisible();
    await expect(page.locator('#sameNote')).toContainText('두 안이 같습니다');
    await expect(page.locator('#options + #sameNote')).toHaveCount(1); // 안 카드 바로 아래

    // 다른 경우: 두 안의 경로가 다르다
    const diff = await apiSimulate(page, DIFFERENT);
    expect(JSON.stringify(diff.options[0].ships)).not.toBe(JSON.stringify(diff.options[1].ships));
    await openNewOrder(page);
    await setTotal(page, 15000);
    for (const [c, v] of Object.entries(DIFFERENT)) await setGauge(page, c as 'CHINA', v);
    await runSimulation(page);
    await expect(page.locator('#sameNote')).toBeHidden();
  });

  test('확정한 주문은 확정 당시 운영비를 같은 항목으로 보여 준다', async ({ page }) => {
    const id = await createOrder(page, { orders: DIFFERENT });
    const sim = await apiSimulate(page, DIFFERENT);
    const cost = sim.options.find((o: { type: string }) => o.type === 'COST');
    await page.goto(`/orders/${id}`);
    const stats = await readStats(page);
    expect(stats['운영비']).toBe(`${eok(cost.operatingCost)}억원`);
    expect(stats['총 비용']).toBe(`${eok(cost.totalCost)}억원`);
    const rows = await readDetailsTable(page, 5);
    expect(rows.map((r) => r[1])).toEqual((cost.factoryOperations as FactoryOp[]).map((f) => `${f.days}일`));
  });

  test('운영비 도입 전에 확정한 주문은 열리고 운영비는 없음, 총 비용은 저장값 그대로다', async ({ page }) => {
    const id = await createOrder(page);
    // 운영비 도입 전 주문처럼 계획에서 운영비 값을 뺀 응답으로 연다(저장된 총 비용 = 생산 원가 + 운항비)
    let oldTotal = 0;
    await page.route(`**/api/orders/${id}`, async (route) => {
      const res = await route.fetch();
      const body = await res.json();
      const stored = JSON.parse(body.planJson);
      delete stored.plan.operatingCost;
      delete stored.plan.factoryOperations;
      stored.master.factories.forEach((f: Record<string, unknown>) => delete f.dailyOperatingCostWon);
      stored.plan.totalCost = stored.plan.productionCost + stored.plan.shippingCost;
      oldTotal = stored.plan.totalCost;
      body.planJson = JSON.stringify(stored);
      await route.fulfill({ response: res, json: body });
    });
    await page.goto(`/orders/${id}`);
    const stats = await readStats(page);
    expect(stats['운영비']).toBe('없음');
    expect(stats['총 비용']).toBe(`${eok(oldTotal)}억원`);
    await expect(page.locator('#details')).toContainText('운영비 정보가 없습니다');
    await expect(page.locator('.alert.error')).toHaveCount(0);
    await expect(page.locator('#anim .anim')).toBeVisible();
  });

  test('마스터: 공장마다 하루 운영비(억 원, 기본 10억)를 두고, 1,000억 원 초과와 0 이하는 아무것도 저장하지 않는다', async ({ page, restoreMaster }) => {
    void restoreMaster;
    const before = await readMasterForm(page);
    for (const c of ['CHINA', 'JAPAN', 'VIETNAM', 'INDONESIA']) {
      expect(before[`operatingCost_${c}`], `${c} 기본값`).toBe('10');
    }
    const version = (await (await page.request.get('/api/master')).json()).version;

    // 잘못된 값이 섞여 있으면 올바른 칸도 저장하지 않고 이유를 모아 보여 준다
    await page.locator('input[name="maxDaily_CHINA"]').fill('401');
    await page.locator('input[name="operatingCost_CHINA"]').fill('1000.01');
    await page.locator('input[name="operatingCost_JAPAN"]').fill('0');
    await page.getByRole('button', { name: '저장' }).click();
    const errors = page.locator('.alert.error li');
    await expect(errors).toHaveCount(2);
    await expect(errors.filter({ hasText: '중국 하루 운영비' })).toContainText('1,000억 원 이하');
    await expect(errors.filter({ hasText: '일본 하루 운영비' })).toContainText('0보다 커야');
    const afterBad = await (await page.request.get('/api/master')).json();
    expect(afterBad.version).toBe(version);
    expect(afterBad.factories.find((f: { country: string }) => f.country === 'CHINA').maxDaily).toBe(Number(before.maxDaily_CHINA));

    // 상한 값 1,000억 원은 저장된다. 저장하면 버전이 오르고 공장별로 따로 반영된다
    await page.locator('input[name="operatingCost_CHINA"]').fill('1000');
    await page.locator('input[name="operatingCost_JAPAN"]').fill('2.5');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.locator('.alert.ok')).toHaveText('마스터 데이터를 저장했습니다');
    const after = await (await page.request.get('/api/master')).json();
    expect(after.version).toBe(version + 1);
    const won = (c: string) => after.factories.find((f: { country: string }) => f.country === c).dailyOperatingCostWon;
    expect(won('CHINA')).toBe(1000 * 1e8);
    expect(won('JAPAN')).toBe(2.5 * 1e8);
    expect(won('VIETNAM')).toBe(10 * 1e8);
  });
});
