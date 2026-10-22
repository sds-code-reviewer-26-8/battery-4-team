import { expect, test } from '@playwright/test';
import { createOrder, listRow, login, normalize } from './common-helpers';

// E2E-023 목록에 필요한 정보가 최신순으로 나온다 (PRD R6)
test('E2E-023 목록의 열이 모두 있고 방금 확정한 주문이 맨 위, 번호는 내림차순이다', async ({ page }) => {
  await login(page);
  await createOrder(page.request, { routeType: 'COST', memo: 'E2E-023 첫째' });
  const latest = await createOrder(page.request, { routeType: 'FAST', memo: 'E2E-023 둘째' });

  // 1. [주문 목록]을 누른다
  await page.getByRole('link', { name: '주문 목록', exact: true }).first().click();
  await expect(page).toHaveURL(/\/orders$/);

  // 열: 주문번호, 확정 일시, 총 수량(K), 경로, 소요 일수, 총 비용(억원), 상태, 작성자, 고객사
  const headers = (await page.locator('table.grid thead th').allInnerTexts()).map(normalize);
  expect(headers).toEqual(['주문번호', '확정 일시', '총 수량(K)', '경로', '소요 일수', '총 비용(억원)', '상태', '작성자', '고객사']);

  // 번호가 내림차순이고, 방금 확정한 주문이 가장 위에 있다
  const ids = (await page.locator('table.grid tbody tr.clickable td:first-child').allInnerTexts()).map((t) =>
    Number(normalize(t).replace('#', '')),
  );
  expect(ids.length).toBeGreaterThanOrEqual(2);
  expect(ids[0]).toBe(latest);
  for (let i = 1; i < ids.length; i++) expect(ids[i], `${i}번째 행은 이전 행보다 작아야 한다`).toBeLessThan(ids[i - 1]);

  // 방금 확정한 주문의 행 내용
  const cells = (await listRow(page, latest).locator('td').allInnerTexts()).map(normalize);
  expect(cells[2]).toBe('12,000');
  expect(cells[3]).toBe('가장 빠르게');
  expect(cells[6]).toBe('확정');
  expect(cells[7]).toBe('admin');
  expect(cells).toHaveLength(9);
  expect(cells[8]).toBe('-'); // 고객사를 입력하지 않고 확정했으므로
});
