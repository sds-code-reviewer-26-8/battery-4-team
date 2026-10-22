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

  for (const bad of ['4', '51', '0', '-5', '10.5', 'abc']) {
    test(`잘못된 납기 "${bad}" 는 오류 문구를 보이고 빨간 표시를 하지 않는다`, async ({ page }) => {
      await runSimulation(page);
      await page.locator('#dueAsap').uncheck();
      await page.locator('#dueDays').fill(bad);
      await expect(page.locator('#dueError')).toBeVisible();
      await expect(page.locator('#dueError')).toContainText('5~50');
      await expect(page.locator('#dueHint')).toBeHidden();
      await expect(page.locator('.option.late')).toHaveCount(0);
    });
  }

  test('체크를 해제한 직후 아직 비어 있을 때는 오류가 아니라 안내 문구를 보이고 입력칸에 포커스가 간다', async ({ page }) => {
    await runSimulation(page);
    await page.locator('#dueAsap').uncheck();
    await expect(page.locator('#dueDays')).toBeFocused();
    await expect(page.locator('#dueHint')).toBeVisible();
    await expect(page.locator('#dueHint')).toContainText('5~50');
    await expect(page.locator('#dueError')).toBeHidden();
    await expect(page.locator('.option.late')).toHaveCount(0);

    // 값을 지우면 다시 안내 문구, 잘못된 값이면 오류, 올바른 값이면 둘 다 없다
    await page.locator('#dueDays').fill('4');
    await expect(page.locator('#dueError')).toBeVisible();
    await page.locator('#dueDays').fill('');
    await expect(page.locator('#dueHint')).toBeVisible();
    await expect(page.locator('#dueError')).toBeHidden();
    await page.locator('#dueDays').fill('20');
    await expect(page.locator('#dueHint')).toBeHidden();
    await expect(page.locator('#dueError')).toBeHidden();
  });

  test('납기를 입력해도 요약·상세·지도는 다시 그리지 않고 경로안 카드만 갱신한다', async ({ page }) => {
    await runSimulation(page);
    // 요약 제목에 표지를 달아 두고, 납기를 입력한 뒤에도 같은 요소가 남아 있는지 본다
    await page.evaluate(() => {
      document.querySelector('#summary h2')!.setAttribute('data-keep', '1');
      document.querySelector('#details h2')!.setAttribute('data-keep', '1');
    });
    await page.locator('#dueAsap').uncheck();
    await page.locator('#dueDays').fill('20');
    await expect(page.locator('.option.late').first()).toBeVisible(); // 카드는 갱신됐다
    await expect(page.locator('#summary h2[data-keep="1"]')).toHaveCount(1);
    await expect(page.locator('#details h2[data-keep="1"]')).toHaveCount(1);
  });

  test('오류는 보조 기술에 알려지고 입력칸에 잘못된 값 표시가 붙는다', async ({ page }) => {
    await runSimulation(page);
    await page.locator('#dueAsap').uncheck();
    await expect(page.locator('#dueError')).toHaveAttribute('role', 'alert');
    await expect(page.locator('#dueDays')).toHaveAttribute('aria-invalid', 'false');
    await page.locator('#dueDays').fill('4');
    await expect(page.locator('#dueDays')).toHaveAttribute('aria-invalid', 'true');
    await expect(page.locator('#dueDays')).toHaveAttribute('aria-describedby', /dueError/);
    await page.locator('#dueDays').fill('20');
    await expect(page.locator('#dueDays')).toHaveAttribute('aria-invalid', 'false');
  });

  test('납기 초과 카드의 보조 글자는 연한 분홍 배경에서도 읽을 수 있는 색이다', async ({ page }) => {
    await runSimulation(page);
    await page.locator('#dueAsap').uncheck();
    await page.locator('#dueDays').fill('5'); // 모든 안이 납기를 넘는다
    await expect(page.locator('.option.late .muted').first()).toHaveCSS('color', 'rgb(141, 71, 66)'); // #8d4742: #fdecea 위 대비 5.9:1 (AA 4.5:1 이상)
  });

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

  test('납기를 고르면 주문 확정 요청 본문에 dueDays 가 함께 간다', async ({ page }) => {
    await runSimulation(page);
    await page.locator('#dueAsap').uncheck();
    await page.locator('#dueDays').fill('5');
    const reqPromise = page.waitForRequest((r) => r.url().endsWith('/api/orders') && r.method() === 'POST');
    await page.locator('#confirmBtn').click();
    const body = (await reqPromise).postDataJSON();
    expect(Object.keys(body).sort()).toEqual(['customerName', 'dueDays', 'masterVersion', 'memo', 'orders', 'routeType']);
    expect(body.orders).toEqual(EVEN);
    expect(body.routeType).toBe('COST');
    expect(body.dueDays).toBe(5);
  });

  test('가능한 빨리를 고르면 dueDays 는 null 로 간다', async ({ page }) => {
    await runSimulation(page);
    const reqPromise = page.waitForRequest((r) => r.url().endsWith('/api/orders') && r.method() === 'POST');
    await page.locator('#confirmBtn').click();
    expect((await reqPromise).postDataJSON().dueDays).toBeNull();
  });

  for (const bad of ['', '4', 'abc']) {
    test(`납기 입력이 "${bad}" 이면 확정 요청을 보내지 않고 이유를 보인다`, async ({ page }) => {
      await runSimulation(page);
      await page.locator('#dueAsap').uncheck();
      await page.locator('#dueDays').fill(bad);
      let sent = false;
      page.on('request', (r) => { if (r.url().endsWith('/api/orders') && r.method() === 'POST') sent = true; });
      await page.locator('#confirmBtn').click();
      await expect(page.locator('#confirmError')).toBeVisible();
      await expect(page.locator('#confirmError')).toContainText('5~50');
      expect(sent).toBe(false);
    });
  }
});
