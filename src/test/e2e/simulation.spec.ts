import { test, expect } from './fixtures';
import {
  EVEN, ROUTE_LABEL, type RouteType,
  login, openNewOrder, runSimulation, pickOption, readStats, setGauge, apiSimulate, saveMaster, readMasterForm, num,
} from './helpers';

const TYPES: RouteType[] = ['COST', 'FAST', 'NEAR'];
const eok = (won: number) => (won / 1e8).toFixed(2);

test.describe('3. 경로안 계산·비교', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await openNewOrder(page);
  });

  test('E2E-011 기본 수량으로 시뮬레이션하면 경로안 3개가 나온다', async ({ page }) => {
    await runSimulation(page);

    const options = page.locator('.option');
    await expect(options).toHaveCount(3);
    for (const t of TYPES) {
      const o = page.locator(`.option[data-type="${t}"]`);
      await expect(o).toContainText(ROUTE_LABEL[t]);
      await expect(o).toContainText(/소요 \d+일/);
      await expect(o).toContainText(/총 비용 [\d.]+억원/);
      await expect(o).toContainText(/생산 원가 [\d.]+억원 · 운항비 [\d.]+억원 · 운영비 [\d.]+억원 · 배 \d+척/);
    }
    await expect(page.locator('.option[data-type="COST"]')).toHaveClass(/selected/);

    await expect(page.locator('#summary h2')).toContainText('총비용 최소');
    for (const title of ['나라별 주문 수량', '배 경로', '일별 생산·비용', '계획에 쓴 마스터 값']) {
      await expect(page.locator('#details h2', { hasText: title })).toBeVisible();
    }
    await expect(page.getByRole('heading', { name: '지도 애니메이션' })).toBeVisible();
    await expect(page.locator('#anim .anim')).toBeVisible();
  });

  test('E2E-012 경로안을 바꾸면 화면의 모든 내용이 그 안으로 바뀐다', async ({ page }) => {
    await runSimulation(page);
    const sim = await apiSimulate(page, EVEN);

    for (const t of ['FAST', 'NEAR', 'COST'] as RouteType[]) {
      await pickOption(page, t);
      const expected = sim.options.find((o: { type: string }) => o.type === t);
      await expect(page.locator('#summary h2')).toContainText(ROUTE_LABEL[t]);
      const stats = await readStats(page);
      expect(num(stats['소요 일수'])).toBe(expected.totalDays);
      expect(stats['총 비용']).toBe(`${eok(expected.totalCost)}억원`);
      expect(stats['운항비']).toBe(`${eok(expected.shippingCost)}억원`);
      expect(stats['운영비']).toBe(`${eok(expected.operatingCost)}억원`);
      // 배 경로 표의 가장 늦은 귀환일이 소요 일수와 같다
      const returns = await page.locator('#details table.grid').nth(1).locator('tbody tr').evaluateAll(
        (trs) => trs.map((tr) => (tr.querySelectorAll('td')[4].textContent ?? '')),
      );
      expect(Math.max(...returns.map(num))).toBe(expected.totalDays);
    }
  });

  test('E2E-013 세 안의 기간·비용 대소 관계가 맞는다 (기본 수량)', async ({ page }) => {
    await runSimulation(page);
    const rows: Record<string, { days: number; ship: number; op: number; prod: number; total: number }> = {};
    for (const t of TYPES) {
      await pickOption(page, t);
      const s = await readStats(page);
      rows[t] = { days: num(s['소요 일수']), ship: num(s['운항비']), op: num(s['운영비']), prod: num(s['생산 원가']), total: num(s['총 비용']) };
      // 총 비용 = 생산 원가 + 운항비 + 운영비 (표시가 소수 2자리라 ±0.01 허용)
      expect(Math.abs(rows[t].total - (rows[t].prod + rows[t].ship + rows[t].op))).toBeLessThanOrEqual(0.011);
    }
    expect(rows.FAST.days).toBeLessThanOrEqual(Math.min(rows.COST.days, rows.NEAR.days));
    // 총비용 최소 안은 운항비+운영비가 가장 낮다
    const shipOp = (r: { ship: number; op: number }) => r.ship + r.op;
    expect(shipOp(rows.COST)).toBeLessThanOrEqual(Math.min(shipOp(rows.FAST), shipOp(rows.NEAR)) + 0.011);
    expect(rows.COST.prod).toBe(rows.FAST.prod);
    expect(rows.FAST.prod).toBe(rows.NEAR.prod);
  });

  test.describe('E2E-013 (일부 불일치)', () => {
    test('E2E-013b 배 1척일 때도 가장 빠르게의 소요 일수가 가장 짧다', async ({ page, restoreMaster }) => {
      // 배 1척, 5000/2000/0/8000 에서 '가까운 곳부터'(45일)가 '가장 빠르게'(47일)보다 빨랐던 불일치를 고쳤다 (HJ-S14-08)
      const form = await readMasterForm(page);
      await saveMaster(page, { ...form, shipCount: '1' });
      const sim = await apiSimulate(page, { CHINA: 5000, JAPAN: 2000, VIETNAM: 0, INDONESIA: 8000 });
      const days = Object.fromEntries(sim.options.map((o: { type: string; totalDays: number }) => [o.type, o.totalDays]));
      expect(days.FAST).toBeLessThanOrEqual(Math.min(days.COST, days.NEAR));
    });
  });

  test('E2E-014 총량이 같아도 나라별 배분을 바꾸면 결과가 달라진다', async ({ page }) => {
    await runSimulation(page);
    await pickOption(page, 'COST');
    const even = await readStats(page);

    // 같은 총량(12,000K)에서 배분만 바꾼다: 중국 6,000 / 일본 0 / 베트남 3,000 / 인도네시아 3,000
    await setGauge(page, 'CHINA', 6000);
    await runSimulation(page);
    const skewed = await readStats(page);

    expect(even['총 수량']).toBe(skewed['총 수량']);
    expect(`${even['소요 일수']}/${even['총 비용']}`).not.toBe(`${skewed['소요 일수']}/${skewed['총 비용']}`);
  });
});
