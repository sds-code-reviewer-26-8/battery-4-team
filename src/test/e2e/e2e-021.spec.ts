import { expect, test } from '@playwright/test';
import { DEFAULT_ORDERS, listRow, login, normalize, simulate } from './common-helpers';

// E2E-021 확정 요청에 끼워 넣은 계산값은 무시되고 서버가 다시 계산한다 (PRD R3, INTENT 원칙 2)
test('E2E-021 확정 요청에 끼워 넣은 계산값(totalCost=1, totalDays=1)은 무시된다', async ({ page }) => {
  await login(page);
  const sim = await simulate(page.request);
  const cost = sim.options.find((o) => o.type === 'COST');
  const days = cost.totalDays as number;
  const eok = ((cost.totalCost as number) / 1e8).toFixed(2);
  expect(days, '속인 값(1일)과 서버 계산값이 달라야 시험이 의미가 있다').toBeGreaterThan(1);

  // 1. 수량 3,000K×4, 경로안 COST 와 함께 추가 필드를 보낸다
  const res = await page.request.post('/api/orders', {
    data: {
      orders: DEFAULT_ORDERS,
      routeType: 'COST',
      masterVersion: sim.master.version,
      memo: 'E2E-021',
      totalCost: 1,
      totalDays: 1,
      plan: { totalCost: 1, totalDays: 1 },
    },
  });
  expect(res.status()).toBe(200);
  const { id } = (await res.json()) as { id: number };

  // 2. 목록과 상세에서 확인한다 — 서버가 다시 계산한 값으로 저장되어 있다
  await page.goto('/orders');
  // 목록은 화면이 API 응답으로 그린다. 행이 나타난 뒤에 읽는다. (allInnerTexts 는 기다리지 않는다)
  await expect(listRow(page, id)).toBeVisible();
  const cells = (await listRow(page, id).locator('td').allInnerTexts()).map(normalize);
  expect(cells[4], '목록의 소요 일수').toBe(String(days));
  expect(cells[5], '목록의 총 비용(억원)').toBe(eok);

  await page.goto(`/orders/${id}`);
  // 요약도 화면이 API 응답으로 채운다. 내용이 채워질 때까지 기다린 뒤 읽는다.
  await expect
    .poll(async () => normalize(await page.locator('#summary').innerText()))
    .toContain(`소요 일수 ${days}일`);
  const summary = normalize(await page.locator('#summary').innerText());
  expect(summary).toContain(`소요 일수 ${days}일`);
  expect(summary).toContain(`총 비용 ${eok}억원`);
  expect(summary).not.toContain('소요 일수 1일');
});
