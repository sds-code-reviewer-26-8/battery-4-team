import { test, expect } from './fixtures';
import { EVEN, login, openNewOrder, runSimulation, apiSimulate, type RouteType } from './helpers';

const TYPES: RouteType[] = ['COST', 'FAST', 'NEAR'];

test.describe('필요 납기일 표시', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
    await openNewOrder(page);
  });

  test('기본은 가능한 빨리 체크이고 납기 표시가 없다', async ({ page }) => {
    await expect(page.locator('#dueAsap')).toBeChecked();
    await expect(page.locator('#dueDays')).toBeDisabled();
    await runSimulation(page);
    await expect(page.locator('.option.late')).toHaveCount(0);
    await expect(page.locator('.late-note')).toHaveCount(0);
  });

  test('납기를 입력하면 소요 일수가 납기를 넘는 안만 빨갛게 표시되고, 체크하면 사라진다', async ({ page }) => {
    const sim = await apiSimulate(page, EVEN);
    const days: Record<string, number> = {};
    sim.options.forEach((o: { type: string; totalDays: number }) => (days[o.type] = o.totalDays));
    const min = Math.min(...Object.values(days));
    const max = Math.max(...Object.values(days));

    await runSimulation(page);
    await page.locator('#dueAsap').uncheck();
    await expect(page.locator('#dueDays')).toBeEnabled();

    // 가장 짧은 안과 같은 납기: 더 오래 걸리는 안만 초과
    const due = Math.min(50, Math.max(5, min));
    await page.locator('#dueDays').fill(String(due));
    for (const t of TYPES) {
      const o = page.locator(`.option[data-type="${t}"]`);
      if (days[t] > due) {
        await expect(o).toHaveClass(/late/);
        await expect(o).toContainText(`납기 초과 ${days[t] - due}일`);
      } else {
        await expect(o).not.toHaveClass(/late/);
        await expect(o).not.toContainText('납기 초과');
      }
    }
    await expect(page.locator('#result')).toBeVisible(); // 시뮬레이션 결과는 유지

    // 모든 안이 납기 안: 표시 없음
    if (max <= 50) {
      await page.locator('#dueDays').fill(String(Math.max(max, 5)));
      await expect(page.locator('.option.late')).toHaveCount(0);
    }

    await page.locator('#dueAsap').check();
    await expect(page.locator('.option.late')).toHaveCount(0);
    await expect(page.locator('#dueDays')).toBeDisabled();
  });

  for (const bad of ['4', '51', '0', '-5', '10.5', 'abc', '']) {
    test(`잘못된 납기 "${bad}" 는 오류 문구를 보이고 빨간 표시를 하지 않는다`, async ({ page }) => {
      await runSimulation(page);
      await page.locator('#dueAsap').uncheck();
      await page.locator('#dueDays').fill(bad);
      await expect(page.locator('#dueError')).toBeVisible();
      await expect(page.locator('#dueError')).toContainText('5~50');
      await expect(page.locator('.option.late')).toHaveCount(0);
    });
  }

  test('납기 초과 안이 선택돼도 선택된 안이 다른 초과 안과 구별된다', async ({ page }) => {
    await runSimulation(page);
    await page.locator('#dueAsap').uncheck();
    await page.locator('#dueDays').fill('5'); // 모든 안이 납기를 넘는다

    // 기본 선택은 운항비 최소(COST). 초과 표시와 선택 표시가 함께 보여야 한다
    const selected = page.locator('.option[data-type="COST"]');
    const other = page.locator('.option[data-type="FAST"]');
    await expect(selected).toHaveClass(/late/);
    await expect(selected).toHaveClass(/selected/);
    await expect(other).toHaveClass(/late/);
    await expect(other).not.toHaveClass(/selected/);

    // 선택된 초과 안은 바깥 테두리 링이 있고, 선택되지 않은 초과 안은 없다
    await expect(selected).not.toHaveCSS('box-shadow', 'none');
    await expect(other).toHaveCSS('box-shadow', 'none');

    // 다른 초과 안을 누르면 선택 표시가 옮겨 간다
    await other.click();
    await expect(other).not.toHaveCSS('box-shadow', 'none');
    await expect(selected).toHaveCSS('box-shadow', 'none');
  });

  test('납기를 바꿔도 주문 확정 요청 본문은 기존과 같다', async ({ page }) => {
    await runSimulation(page);
    await page.locator('#dueAsap').uncheck();
    await page.locator('#dueDays').fill('5');
    const reqPromise = page.waitForRequest((r) => r.url().endsWith('/api/orders') && r.method() === 'POST');
    await page.locator('#confirmBtn').click();
    const body = (await reqPromise).postDataJSON();
    expect(Object.keys(body).sort()).toEqual(['masterVersion', 'memo', 'orders', 'routeType']);
    expect(body.orders).toEqual(EVEN);
    expect(body.routeType).toBe('COST');
  });
});
