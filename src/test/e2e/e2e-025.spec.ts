import { expect, test } from '@playwright/test';
import { createOrder, login, normalize, readHistory } from './common-helpers';

// E2E-025 상세에 확정 당시 내용과 이력이 모두 보인다 (PRD R6·R4, INTENT 원칙 4·9)
test('E2E-025 주문 상세에 확정 당시 내용, 이력, 애니메이션이 모두 있고 애니메이션은 자동 재생되지 않는다', async ({ page }) => {
  await login(page);
  const id = await createOrder(page.request, { routeType: 'FAST', memo: 'E2E-025 메모' });

  // 1. 주문 상세를 연다
  await page.goto(`/orders/${id}`);
  await expect(page.locator('h1')).toContainText(`주문 #${id}`);
  await expect(page.locator('h1 .badge')).toHaveText('확정');

  // 확정 일시, 작성자, 경로, 메모
  const meta = normalize(await page.locator('dl.meta').innerText());
  expect(meta).toMatch(/확정 일시 \d{4}-\d{2}-\d{2} \d{2}:\d{2}/);
  expect(meta).toContain('작성자 admin');
  expect(meta).toContain('경로 가장 빠르게');
  expect(meta).toContain('메모 E2E-025 메모');

  // 상태 이력: 확정 1행 (이전 상태 '-')
  expect(await readHistory(page)).toEqual([{ from: '-', to: '확정', by: 'admin' }]);

  // 요약
  const summary = normalize(await page.locator('#summary').innerText());
  for (const word of ['요약', '총 수량', '소요 일수', '생산 원가', '운항비', '운영비', '총 비용']) expect(summary).toContain(word);

  // 나라별 주문 수량·방문 횟수, 배 경로, 일별 생산·비용(펼치기), 계획에 쓴 마스터 값(펼치기)
  const headings = (await page.locator('#details h2').allInnerTexts()).map(normalize);
  expect(headings).toEqual(['나라별 주문 수량', '배 경로 (왕복별 일정)', '일별 생산·비용', '계획에 쓴 마스터 값', '공장별 운영 일수·운영비']);
  const countryTable = normalize(await page.locator('#details > table.grid').first().innerText());
  expect(countryTable).toContain('필요 방문 횟수');
  const summaries = (await page.locator('#details details summary').allInnerTexts()).map(normalize);
  expect(summaries[0]).toMatch(/^\d+일 보기$/);
  expect(summaries[1]).toBe('보기');

  // 지도 애니메이션이 있고 자동 재생되지 않는다
  await expect(page.locator('#anim .anim')).toBeVisible();
  await expect(page.locator('[data-play]')).toHaveText('재생');
  const day = normalize(await page.locator('.anim-day').innerText());
  expect(day).toMatch(/^0일 \/ \d+일$/);
  await page.waitForTimeout(1500);
  expect(normalize(await page.locator('.anim-day').innerText()), '1.5초 뒤에도 0일에 머물러야 한다').toBe(day);
});
