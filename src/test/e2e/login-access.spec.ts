import { test, expect, type Page } from '@playwright/test';
import { loginUi } from './helpers';

/** 화면으로 로그인하고 주문 목록까지 이동했는지 확인한다 */
async function loginAsAdmin(page: Page) {
  await loginUi(page);
  await expect(page).toHaveURL(/\/orders$/);
}

test.describe('1. 로그인·접근 통제', () => {
  test('E2E-001 올바른 계정으로 로그인한다', async ({ page }) => {
    await loginUi(page, 'admin', 'admin');
    await expect(page).toHaveURL(/\/orders$/);
    await expect(page.getByRole('heading', { name: '주문 목록' })).toBeVisible();
    await expect(page.locator('.topbar .user span')).toHaveText('admin');
  });

  test('E2E-002 틀린 아이디·비밀번호는 이유와 함께 거부된다', async ({ page }) => {
    for (const [id, pw] of [['admin', 'wrong'], ['guest', 'admin']]) {
      await loginUi(page, id, pw);
      // 세션 쿠키가 없는 첫 요청에는 서버가 URL 에 ;jsessionid 를 붙일 수 있다
      await expect(page).toHaveURL(/\/login(;jsessionid=\w+)?$/);
      await expect(page.locator('.alert.error')).toHaveText('아이디 또는 비밀번호가 올바르지 않습니다');
      await expect(page.getByRole('heading', { name: '주문 목록' })).toHaveCount(0);
    }
  });

  test('E2E-003 로그인하지 않으면 모든 화면이 막힌다', async ({ page, request }) => {
    for (const path of ['/orders', '/orders/new', '/orders/1', '/master']) {
      // 화면 내용 없이 곧바로 /login 으로 보내는지
      const res = await request.get(path, { maxRedirects: 0 });
      expect(res.status(), path).toBe(302);
      expect(res.headers()['location'], path).toMatch(/\/login$/);

      await page.goto(path);
      await expect(page, path).toHaveURL(/\/login$/);
      await expect(page.getByRole('heading', { name: '배터리 주문 관리' })).toBeVisible();
    }
  });

  test('E2E-004 로그인하지 않으면 API 가 401 을 돌려준다', async ({ page, request }) => {
    // 주문 건수 기준값
    await loginAsAdmin(page);
    const totalChip = page.locator('.filters .chip').first().locator('.count');
    const before = await totalChip.innerText();

    const anon = await request.get('/api/master', { maxRedirects: 0 });
    expect(anon.status()).toBe(401);

    const sim = await request.post('/api/simulate', { data: { orders: { CHINA: 1000 } }, maxRedirects: 0 });
    expect(sim.status()).toBe(401);

    const confirm = await request.post('/api/orders', {
      data: { orders: { CHINA: 1000 }, routeType: 'COST', masterVersion: 1, memo: '' },
      maxRedirects: 0,
    });
    expect(confirm.status()).toBe(401);

    // 주문이 생성되지 않았다
    await page.goto('/orders');
    await expect(totalChip).toHaveText(before);
  });

  test('E2E-005 로그아웃하면 다시 로그인해야 한다', async ({ page }) => {
    await loginAsAdmin(page);

    await page.getByRole('button', { name: '로그아웃' }).click();
    await expect(page).toHaveURL(/\/login$/);

    await page.goto('/orders');
    await expect(page).toHaveURL(/\/login$/);

    await page.goBack();
    await expect(page.getByRole('heading', { name: '주문 목록' })).toHaveCount(0);
    await expect(page.locator('table.grid')).toHaveCount(0);
  });
  test('E2E-042 가입 화면에서 가입한 뒤 로그인하고, 로그인 전에는 여전히 막힌다', async ({ page }) => {
    const username = `user${Date.now() % 1e9}`;
    await page.goto('/login');
    await page.getByRole('link', { name: '회원 가입' }).click();
    await expect(page).toHaveURL(/\/signup$/);

    // 규칙 위반은 이유와 함께 거부된다
    await page.locator('input[name=username]').fill('admin');
    await page.locator('input[name=password]').fill('abc');
    await page.getByRole('button', { name: '가입' }).click();
    await expect(page.locator('.alert.error')).toContainText('사용할 수 없는 아이디입니다');

    await page.locator('input[name=username]').fill(username);
    await page.locator('input[name=password]').fill('pass1234');
    await page.getByRole('button', { name: '가입' }).click();
    await expect(page).toHaveURL(/\/login$/);

    await page.goto('/orders');
    await expect(page).toHaveURL(/\/login$/);

    await loginUi(page, username, 'pass1234');
    await expect(page).toHaveURL(/\/orders$/);
    await expect(page.locator('.topbar .user span')).toHaveText(username);
  });

  test('E2E-043 한도를 넘겨 429 가 오면 로그인·가입 화면에 이유가 보인다', async ({ page }) => {
    // E2E 앱은 한도를 풀어 두었으므로 서버 응답을 가로채 429 를 만든다. 화면이 이유를 그대로 보여 주는지만 확인한다.
    // (한도 계산과 429 응답 자체는 JUnit: RateLimitTests, RateLimitWiringTests)
    const message = '요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요';
    const tooMany = (route: import('@playwright/test').Route) =>
      route.fulfill({
        status: 429,
        contentType: 'application/json; charset=utf-8',
        headers: { 'Retry-After': '30' },
        body: JSON.stringify({ error: message, conflict: false }),
      });

    await page.route('**/api/login', tooMany);
    await loginUi(page, 'admin', 'admin');
    await expect(page).toHaveURL(/\/login(;jsessionid=\w+)?$/);
    await expect(page.locator('.alert.error')).toHaveText(message);

    await page.route('**/api/signup', tooMany);
    await page.goto('/signup');
    await page.locator('input[name=username]').fill(`user${Date.now() % 1e9}`);
    await page.locator('input[name=password]').fill('pass1234');
    await page.getByRole('button', { name: '가입' }).click();
    await expect(page.locator('.alert.error')).toHaveText(message);
  });

  test('E2E-044 로그인하면 세션 ID 가 바뀌고, 로그인 전의 세션 ID 로는 접근할 수 없다', async ({ page, playwright, baseURL }) => {
    // 로그인 전 세션: 틀린 비밀번호로 한 번 요청하면 서버가 세션 쿠키를 만든다
    const failed = await page.request.post('/api/login', { data: { username: 'admin', password: 'wrong' } });
    expect(failed.status()).toBe(400);
    const sessionCookie = async () => (await page.context().cookies()).find((c) => c.name === 'JSESSIONID');
    const before = await sessionCookie();
    expect(before, '로그인 전 세션 쿠키').toBeDefined();

    const ok = await page.request.post('/api/login', { data: { username: 'admin', password: 'admin' } });
    expect(ok.status()).toBe(200);
    const after = await sessionCookie();
    expect(after, '로그인 뒤 세션 쿠키').toBeDefined();
    expect(after!.value).not.toBe(before!.value);

    // 새 세션은 로그인 상태다
    expect((await page.request.get('/api/me')).status()).toBe(200);

    // 로그인 전에 쥐고 있던 세션 ID 로는 로그인 상태가 되지 않는다 (세션 고정 방지)
    const old = await playwright.request.newContext({
      baseURL,
      extraHTTPHeaders: { Cookie: `JSESSIONID=${before!.value}` },
    });
    expect((await old.get('/api/me')).status()).toBe(401);
    await old.dispose();
  });
});
