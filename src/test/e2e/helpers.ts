import { expect, type Locator, type Page } from '@playwright/test';

export const COUNTRIES = ['CHINA', 'JAPAN', 'VIETNAM', 'INDONESIA'] as const;
export type Country = (typeof COUNTRIES)[number];
export type RouteType = 'COST' | 'FAST' | 'NEAR';
export type Orders = Record<Country, number>;

export const ROUTE_LABEL: Record<RouteType, string> = { COST: '총비용 최소', FAST: '가장 빠르게', NEAR: '가까운 곳부터' };
export const STATUS_LABEL = { IN_PROGRESS: '진행', COMPLETED: '완료', CANCELED: '취소' } as const;

/** 기본 수량: 4개국 각 3,000K (총 12,000K) */
export const EVEN: Orders = { CHINA: 3000, JAPAN: 3000, VIETNAM: 3000, INDONESIA: 3000 };

/** "26일", "98.00억원", "12,000K" 같은 문구에서 숫자만 꺼낸다 */
export const num = (text: string): number => parseFloat(text.replace(/[^0-9.\-]/g, ''));

// ------------------------------------------------------------------ 로그인

/** 화면을 거치지 않고 로그인한다. 세션 쿠키는 이 page 의 컨텍스트에 저장된다. */
export async function login(page: Page): Promise<void> {
  const res = await page.request.post('/api/login', { data: { username: 'admin', password: 'admin' } });
  expect(res.status()).toBe(200);
}

/** 화면으로 로그인한다. */
export async function loginUi(page: Page, username = 'admin', password = 'admin'): Promise<void> {
  await page.goto('/login');
  await page.locator('input[name=username]').fill(username);
  await page.locator('input[name=password]').fill(password);
  await page.getByRole('button', { name: '로그인' }).click();
}

// ------------------------------------------------------------------ API

export async function apiMaster(page: Page) {
  const res = await page.request.get('/api/master');
  expect(res.status()).toBe(200);
  return res.json();
}

export async function apiSimulate(page: Page, orders: Partial<Orders>) {
  const res = await page.request.post('/api/simulate', { data: { orders } });
  expect(res.status()).toBe(200);
  return res.json();
}

/** 주문을 API 로 확정하고 주문 번호를 돌려준다. */
export async function createOrder(
  page: Page,
  opts: { orders?: Partial<Orders>; routeType?: RouteType; memo?: string } = {},
): Promise<number> {
  const master = await apiMaster(page);
  const res = await page.request.post('/api/orders', {
    data: {
      orders: opts.orders ?? EVEN,
      routeType: opts.routeType ?? 'COST',
      masterVersion: master.version,
      memo: opts.memo ?? '',
    },
  });
  expect(res.status(), await res.text()).toBe(200);
  return (await res.json()).id as number;
}

// ------------------------------------------------------------------ 주문 목록·상세

/** 주문 목록의 '전체' 칩 건수 */
export async function orderCount(page: Page): Promise<number> {
  await page.goto('/orders');
  return num(await page.locator('.filters .chip').first().locator('.count').innerText());
}

/** 상세 화면에서 상태를 바꾼다(확인 창은 수락). 취소는 이동한 화면이 오류일 수 있어 결과를 기다리지 않는다. */
export async function changeStatusUi(page: Page, id: number, to: keyof typeof STATUS_LABEL): Promise<void> {
  await page.goto(`/orders/${id}`);
  page.once('dialog', (d) => d.accept());
  await page.getByRole('button', { name: `${STATUS_LABEL[to]}(으)로 변경` }).click();
  if (to !== 'CANCELED') {
    await expect(page.locator('.alert.ok')).toBeVisible();
  }
}

/** 상세 화면의 확정 정보(dl.meta) 한 칸 */
export const meta = (page: Page, label: string): Locator =>
  page.locator('.meta > div').filter({ has: page.locator('dt', { hasText: label }) }).locator('dd');

/** 상태 이력 표: [일시, 이전 상태, 새 상태, 변경한 사람] 배열. 화면이 이력을 그릴 때까지 기다린 뒤 읽는다. */
export async function historyRows(page: Page): Promise<string[][]> {
  const rows = page
    .locator('section.card')
    .filter({ has: page.getByRole('heading', { name: '상태 이력' }) })
    .locator('tbody tr');
  await rows.first().waitFor();
  return rows.evaluateAll((trs) => trs.map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => (td.textContent ?? '').trim())));
}

/** 요약 카드(총 수량, 소요 일수, 생산 원가, 운항비, 운영비, 총 비용) */
export async function readStats(page: Page): Promise<Record<string, string>> {
  await page.locator('#summary .stats > div').first().waitFor();
  return page.locator('#summary .stats > div').evaluateAll((divs) => {
    const out: Record<string, string> = {};
    divs.forEach((d) => {
      out[(d.querySelector('span')?.textContent ?? '').trim()] = (d.querySelector('b')?.textContent ?? '').trim();
    });
    return out;
  });
}

/** #details 안의 n 번째 표(0: 나라별 주문 수량, 1: 배 경로, 2: 일별, 3: 공장 값, 4: 항로)의 행별 셀 텍스트 */
export async function readDetailsTable(page: Page, index: number): Promise<string[][]> {
  const rows = page.locator('#details table.grid').nth(index).locator('tbody tr');
  await rows.first().waitFor();
  return rows.evaluateAll((trs) => trs.map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => (td.textContent ?? '').trim())));
}

/** 배 경로 표의 경로 칸에서 나라별 적재량(K)을 모두 더한다. 예: "한국 → 중국(1일 도착 · 3,000K 적재) → 한국" */
export async function loadedByCountry(page: Page): Promise<Record<string, number>> {
  const rows = await readDetailsTable(page, 1);
  const sum: Record<string, number> = {};
  for (const r of rows) {
    for (const m of r[3].matchAll(/([가-힣]+)\s*\(\d+일 도착 · ([\d,]+)K 적재\)/g)) {
      sum[m[1]] = (sum[m[1]] ?? 0) + num(m[2]);
    }
  }
  return sum;
}

// ------------------------------------------------------------------ 주문 추가 화면

export const gauge = (page: Page, c: Country): Locator => page.locator(`.gauge[data-c="${c}"] .gnum`);

export async function setGauge(page: Page, c: Country, value: number): Promise<void> {
  await gauge(page, c).fill(String(value));
  await gauge(page, c).blur();
}

export async function setTotal(page: Page, value: number | string): Promise<void> {
  await page.locator('#total').fill(String(value));
  await page.locator('#total').blur();
}

export async function gaugeValues(page: Page): Promise<number[]> {
  return Promise.all(COUNTRIES.map(async (c) => Number(await gauge(page, c).inputValue())));
}

/** 주문 추가 화면에서 [시뮬레이션 실행]을 누르고 결과가 보일 때까지 기다린다. */
export async function runSimulation(page: Page): Promise<void> {
  await page.locator('#runBtn').click();
  await expect(page.locator('#result')).toBeVisible();
}

export async function openNewOrder(page: Page): Promise<void> {
  await page.goto('/orders/new');
  await expect(page.locator('#runBtn')).toBeVisible();
}

export async function pickOption(page: Page, type: RouteType): Promise<void> {
  await page.locator(`.option[data-type="${type}"]`).click();
  await expect(page.locator(`.option[data-type="${type}"]`)).toHaveClass(/selected/);
}

// ------------------------------------------------------------------ 마스터

/** 마스터 화면의 모든 입력값을 {이름: 값}으로 읽는다. 복원에 쓴다. */
export async function readMasterForm(page: Page): Promise<Record<string, string>> {
  await page.goto('/master');
  await page.locator('form[action="/master"] input[name]').first().waitFor(); // 화면이 입력칸을 그릴 때까지
  return page.evaluate(() => {
    const out: Record<string, string> = {};
    document.querySelectorAll<HTMLInputElement>('form[action="/master"] input[name]').forEach((i) => {
      out[i.name] = i.value;
    });
    return out;
  });
}

/** 마스터 값을 API 로 저장한다(화면 검증을 거치지 않는다). 성공하면 200. */
export async function saveMaster(page: Page, values: Record<string, string>) {
  return page.request.post('/api/master', { data: values });
}
