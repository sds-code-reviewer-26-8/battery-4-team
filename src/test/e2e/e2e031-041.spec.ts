import { test, expect, type Page } from '@playwright/test';
import { apiMaster, createOrder, historyRows, login, loginUi, readMasterForm, saveMaster } from './helpers';

/**
 * E2E-031 ~ E2E-041 (담당 시나리오 묶음)
 *
 *   031 허용되지 않은 상태 변경 거부        036~040 마스터 데이터 (serial, 끝에서 원복)
 *   032 취소하면 주문 삭제                  041 로그인~로그아웃 종단 흐름
 *   033 취소 직후 이전 페이지로 이동 (test.fail)
 *   034 동시 변경 시 한쪽만 인정
 *   035 없는 주문 번호 안내 (test.fail)
 *
 * 테스트 이름 앞의 번호(E2E-0xx)는 시나리오 엑셀의 ID 와 같다.
 *
 * 공용 helpers.ts 의 login/loginUi/createOrder/apiMaster/historyRows/readMasterForm/saveMaster 를 쓰고,
 * 공용에 없는 보조 함수는 아래 '이 파일 전용 보조 함수'에 둔다.
 */

// ---------------------------------------------------------------------------
// 이 파일 전용 보조 함수
// ---------------------------------------------------------------------------

/** 이미 열려 있는 주문 상세 화면에서 [OO(으)로 변경] 버튼을 누르고 확인 창을 수락한다. 서버 응답을 받을 때까지 기다린다. */
async function changeStatusByButton(page: Page, label: '진행' | '완료' | '취소') {
  page.once('dialog', (dialog) => dialog.accept());
  // 요청이 서버에 도달해 응답을 받을 때까지 기다린다. (바로 다른 화면으로 이동하면 요청이 끊길 수 있다)
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/status') && r.request().method() === 'POST'),
    page.getByRole('button', { name: `${label}(으)로 변경` }).click(),
  ]);
  await page.waitForLoadState('domcontentloaded');
}

/** 주문 목록의 상태 칩 건수. label 은 '전체', '확정', '진행', '완료', '취소'. */
async function chipCount(page: Page, label: string): Promise<number> {
  const text = await page.locator('.filters .chip', { hasText: label }).locator('.count').innerText();
  return Number(text.trim());
}

/** 주문 상세 API 가 알려 주는 version 값(화면이 상태 변경 때 보내는 값). */
async function readVersion(page: Page, orderId: number): Promise<number> {
  const res = await page.request.get(`/api/orders/${orderId}`);
  expect(res.status(), '주문 상세 API 가 version 값을 주지 않았다').toBe(200);
  return Number((await res.json()).version);
}

/** 화면에 버튼이 없는 상태 변경 요청을 직접 보낸다. 서버가 돌려준 응답 본문(안내·오류 문구가 담긴 JSON)을 돌려준다. */
async function postStatus(page: Page, orderId: number, status: string, version: number): Promise<string> {
  const res = await page.request.post(`/api/orders/${orderId}/status`, { data: { status, version } });
  return res.text();
}

/** 주문 상세 화면의 상태 이력을 '이전 → 새 상태' 문자열 배열로 읽는다. */
async function readHistory(page: Page): Promise<string[]> {
  return (await historyRows(page)).map((cells) => `${cells[1]} → ${cells[2]}`);
}

// ======================================================================
// E2E-031
// ======================================================================
/**
 * E2E-031 허용되지 않은 상태 변경은 직접 요청해도 거부된다
 *
 * 화면에는 허용된 다음 상태 버튼만 있지만, 서버도 같은 규칙으로 막는지 확인한다.
 * 버튼이 없는 요청이라 상태 변경 요청을 직접 보낸다(page.request).
 *   1. '확정' 주문을 '완료'로 바로 바꾸기(단계 건너뛰기)
 *   2. '완료' 주문을 '취소'로 바꾸기(최종 상태)
 * 두 경우 모두 상태가 그대로이고 이력 행이 늘지 않아야 한다.
 */
test('E2E-031 허용되지 않은 상태 변경은 직접 요청해도 거부된다', async ({ page }) => {
  await login(page);

  // 1. 확정 → 완료 (건너뛰기)
  const confirmedId = await createOrder(page, { memo: 'E2E-031 확정' });
  const confirmedVersion = await readVersion(page, confirmedId);

  const html1 = await postStatus(page, confirmedId, 'COMPLETED', confirmedVersion);
  expect(html1).toContain('확정 상태에서는 완료(으)로 바꿀 수 없습니다');

  await page.goto(`/orders/${confirmedId}`);
  await expect(page.locator('h1 .badge')).toHaveText('확정');
  expect(await readHistory(page)).toEqual(['- → 확정']);

  // 2. 완료 → 취소 (최종 상태). 완료 주문은 화면에 version 이 없어 변경 횟수로 계산한다.
  const doneId = await createOrder(page, { memo: 'E2E-031 완료' });
  const v0 = await readVersion(page, doneId);
  await postStatus(page, doneId, 'IN_PROGRESS', v0);
  await postStatus(page, doneId, 'COMPLETED', v0 + 1);

  const html2 = await postStatus(page, doneId, 'CANCELED', v0 + 2);
  expect(html2).toContain('완료 상태에서는 취소(으)로 바꿀 수 없습니다');

  await page.goto(`/orders/${doneId}`);
  await expect(page.locator('h1 .badge')).toHaveText('완료');
  await expect(page.getByText('최종 상태라 더 바꿀 수 없습니다.')).toBeVisible();
  expect(await readHistory(page)).toEqual(['- → 확정', '확정 → 진행', '진행 → 완료']);
});

// ======================================================================
// E2E-032
// ======================================================================
/**
 * E2E-032 확정·진행 주문을 취소하면 목록에서 사라지고 이력은 남는다
 *
 * 취소하면 주문 행은 삭제되고 상태 이력만 남는다(확정된 정책).
 *   - 주문 목록에서 해당 주문이 사라진다.
 *   - 취소해도 '취소' 칩 건수는 늘지 않고, '취소' 필터에도 나오지 않는다.
 *   - 확정 주문과 진행 주문 모두 같은 결과다.
 *
 * 한계: 시나리오의 "DB(order_status_history)에 이력이 남아 있다" 확인은 화면에서 볼 수 없고
 *       Playwright 에서 DB 에 붙을 수 없어 이 테스트에 넣지 않았다. (JUnit/수동 확인 필요)
 * 참고: 취소 직후 이동은 E2E-033 이 확인한다. 이 테스트는 목록을 직접 연다.
 */
async function expectCancelRemovesOrder(page: Page, orderId: number) {
  // 취소 전 상태 기록
  await page.goto('/orders');
  const canceledBefore = await chipCount(page, '취소');

  // 상세에서 취소
  await page.goto(`/orders/${orderId}`);
  await changeStatusByButton(page, '취소');

  // 목록을 직접 연다: 주문이 사라졌고, 전체 건수는 표의 행 수와 맞는다
  await page.goto('/orders');
  await expect(page.locator(`a[href="/orders/${orderId}"]`)).toHaveCount(0);
  expect(await chipCount(page, '전체')).toBe(await page.locator('table tbody tr').count());

  // '취소' 건수는 늘지 않고, '취소' 필터에도 나오지 않는다
  const canceledAfter = await chipCount(page, '취소');
  expect(canceledAfter).toBe(canceledBefore);
  await page.goto('/orders?status=CANCELED');
  await expect(page.locator(`a[href="/orders/${orderId}"]`)).toHaveCount(0);
  if (canceledAfter === 0) {
    await expect(page.getByText('주문이 없습니다.')).toBeVisible();
  }
}

test.describe('E2E-032 취소하면 주문은 삭제된다', () => {
  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test("'확정' 주문을 취소하면 목록에서 사라진다", async ({ page }) => {
    const id = await createOrder(page, { memo: 'E2E-032 확정' });
    await expectCancelRemovesOrder(page, id);
  });

  test("'진행' 주문을 취소해도 목록에서 사라진다", async ({ page }) => {
    const id = await createOrder(page, { memo: 'E2E-032 진행' });
    await page.goto(`/orders/${id}`);
    await changeStatusByButton(page, '진행');
    await expect(page.locator('h1 .badge')).toHaveText('진행');
    await expectCancelRemovesOrder(page, id);
  });
});

// ======================================================================
// E2E-033
// ======================================================================
/**
 * E2E-033 취소 직후에는 이전 페이지로 이동한다
 *
 * 정한 동작(PRD 7장): 취소 직후 이전 페이지(주문 목록)로 이동하고 오류 화면이 열리지 않는다.
 * 이전에는 삭제된 주문의 상세(/orders/{번호})로 이동해 HTTP 500 기본 오류 화면이 열렸다. (BUG-001 로 수정)
 */
test('E2E-033 취소 직후에는 이전 페이지(주문 목록)로 이동한다', async ({ page }) => {
  await login(page);
  const id = await createOrder(page, { memo: 'E2E-033' });

  // 목록에서 들어와 상세로 간다 ("이전 페이지"는 주문 목록)
  await page.goto('/orders');
  await page.locator(`a[href="/orders/${id}"]`).click();
  await expect(page).toHaveURL(new RegExp(`/orders/${id}$`));

  await changeStatusByButton(page, '취소');

  // 정한 동작: 주문 목록으로 이동하고 오류 화면이 아니다
  await expect(page).toHaveURL(/\/orders$/);
  await expect(page.getByRole('heading', { name: '주문 목록' })).toBeVisible();
  await expect(page.getByText('Whitelabel Error Page')).toHaveCount(0);

  // 성공 안내가 목록에 보인다
  await expect(page.locator('.alert.ok')).toContainText("상태를 '취소'(으)로 바꿨습니다");
});

/**
 * 취소 후 목록의 성공 안내는 한 번만 보이고, 새로고침하면 사라진다 (DEV-001)
 */
test('취소 후 목록의 성공 안내는 새로고침하면 사라진다', async ({ page }) => {
  await login(page);
  const id = await createOrder(page, { memo: 'DEV-001' });
  await page.goto(`/orders/${id}`);
  await changeStatusByButton(page, '취소');

  await expect(page).toHaveURL(/\/orders$/);
  await expect(page.locator('.alert.ok')).toContainText("상태를 '취소'(으)로 바꿨습니다");

  await page.reload();
  await expect(page.getByRole('heading', { name: '주문 목록' })).toBeVisible();
  await expect(page.locator('.alert.ok')).toHaveCount(0);
});

/**
 * 필터로 보던 목록에서 들어와 취소하면 같은 필터의 목록으로 돌아온다 (BUG-002)
 */
test('필터(확정)로 보던 목록에서 주문을 취소하면 같은 필터 목록으로 돌아온다', async ({ page }) => {
  await login(page);
  const id = await createOrder(page, { memo: 'BUG-002' });

  await page.goto('/orders?status=CONFIRMED');
  await page.locator(`a[href="/orders/${id}"]`).click();
  await expect(page).toHaveURL(new RegExp(`/orders/${id}$`));

  await changeStatusByButton(page, '취소');

  await expect(page).toHaveURL(/\/orders\?status=CONFIRMED$/);
  await expect(page.locator('.filters .chip', { hasText: '확정' })).toHaveClass(/active/);
  await expect(page.locator('.alert.ok')).toContainText("상태를 '취소'(으)로 바꿨습니다");
});

// ======================================================================
// E2E-034
// ======================================================================
/**
 * E2E-034 두 사람이 같은 주문을 동시에 바꾸면 먼저 한 쪽만 인정된다
 *
 * 두 사람(브라우저 컨텍스트 2개, 각자 로그인)이 같은 '확정' 주문 상세를 열어 둔다.
 *   - A: [진행(으)로 변경] → 성공
 *   - B: (새로 고치지 않은 낡은 화면에서) [취소(으)로 변경] → 충돌로 거부
 * B 의 요청은 낙관적 락(version)에 걸려야 하고, A 의 변경이 덮어써지거나 주문이 삭제되면 안 된다.
 */
test('E2E-034 같은 주문을 동시에 바꾸면 먼저 한 쪽만 인정된다', async ({ browser }) => {
  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  const a = await ctxA.newPage();
  const b = await ctxB.newPage();
  try {
    await login(a);
    await login(b);
    const id = await createOrder(a, { memo: 'E2E-034' });

    // 두 사람이 같은 주문 상세를 연다 (같은 version 을 본다)
    await a.goto(`/orders/${id}`);
    await b.goto(`/orders/${id}`);
    await expect(a.locator('h1 .badge')).toHaveText('확정');
    await expect(b.locator('h1 .badge')).toHaveText('확정');

    // A 가 먼저 '진행'으로 바꾼다
    await changeStatusByButton(a, '진행');
    await expect(a.locator('h1 .badge')).toHaveText('진행');
    await expect(a.locator('.alert.ok')).toContainText("상태를 '진행'(으)로 바꿨습니다");

    // B 는 새로 고치지 않은 낡은 화면에서 '취소'를 누른다
    await changeStatusByButton(b, '취소');
    await expect(b.locator('.alert.error')).toContainText('다른 사용자가 먼저 이 주문을 변경했습니다');

    // 새로 고쳐도 상태는 '진행'이고 주문은 삭제되지 않았으며 이력에는 A 의 변경만 있다
    await b.goto(`/orders/${id}`);
    await expect(b.locator('h1 .badge')).toHaveText('진행');
    expect(await readHistory(b)).toEqual(['- → 확정', '확정 → 진행']);
  } finally {
    await ctxA.close();
    await ctxB.close();
  }
});

// ======================================================================
// E2E-035
// ======================================================================
/**
 * E2E-035 없는 주문 번호로 접속하면 이유를 알려 준다
 *
 * 정한 동작(PRD 8장 오류 안내, INTENT 원칙 8): '주문을 찾을 수 없습니다' 같은 안내가 보인다.
 * 현재 구현: 안내 없이 HTTP 500 기본 오류 화면(Whitelabel Error Page)이 열린다.
 *           (알려진 결함: INTENT 9장 2번)
 *
 * 고치기 전까지 test.fail() 로 표시한다. 이 테스트는 "실패하는 것이 정상"이다.
 * 고치고 나면 test.fail() 줄을 지워 일반 테스트로 전환한다.
 */
test('E2E-035 없는 주문 번호로 접속하면 이유를 알려 준다', async ({ page }) => {
  test.fail(true, '알려진 결함: 없는 주문 번호 접근 시 HTTP 500 기본 오류 화면 (INTENT 9장 2번)');

  await login(page);
  const response = await page.goto('/orders/999999');

  // 서버 오류(5xx)가 아니어야 하고, 이유를 알리는 안내가 보여야 한다
  expect(response?.status()).toBeLessThan(500);
  await expect(page.getByText('주문을 찾을 수 없습니다')).toBeVisible();
  await expect(page.getByText('Whitelabel Error Page')).toHaveCount(0);
});

// ======================================================================
// E2E-036 ~ E2E-040
// ======================================================================
/**
 * 기준 정보(마스터 데이터) E2E: E2E-036 ~ E2E-040
 *
 * 마스터는 모든 사용자가 같은 값을 쓰므로, 값을 바꾸는 테스트는 서로 간섭한다.
 *  - 이 describe 의 테스트는 한 번에 하나씩 순서대로 실행한다(serial).
 *  - 값을 바꾸는 테스트는 끝에서 반드시 원래 값으로 되돌린다(afterEach).
 *  - 다른 테스트와 동시에 돌리면 기대 값이 깨질 수 있으니 `--workers=1` 로 실행하는 것을 권한다.
 */
test.describe('E2E-036~040 마스터 데이터', () => {
  test.describe.configure({ mode: 'serial' });

  let original: any; // 테스트 시작 시점의 마스터(JSON)
  let originalForm: Record<string, string>; // 같은 시점의 마스터 화면 입력값(복원용)

  test.beforeEach(async ({ page }) => {
    await login(page);
    original = await apiMaster(page);
    originalForm = await readMasterForm(page);
  });

  test.afterEach(async ({ page }) => {
    // 어떤 테스트가 값을 바꿨든 시작 시점 값으로 되돌린다
    const res = await saveMaster(page, originalForm);
    expect(res.status()).toBe(200);
  });

  /** E2E-036 마스터 화면에 현재 값이 보인다 */
  test('E2E-036 마스터 화면에 현재 값이 보인다', async ({ page }) => {
    await page.goto('/orders');
    await page.getByRole('link', { name: '마스터 데이터' }).click();
    await expect(page).toHaveURL(/\/master$/);
    await expect(page.getByRole('heading', { name: '마스터 데이터' })).toBeVisible();

    // 화면의 값이 서버가 알려 주는 현재 값과 같다 (초기값 DB 가 아니어도 통과하도록 API 값과 비교)
    for (const f of original.factories) {
      await expect(page.locator(`input[name="maxDaily_${f.country}"]`)).toHaveValue(String(f.maxDaily));
      await expect(page.locator(`input[name="stockLimit_${f.country}"]`)).toHaveValue(String(f.stockLimit));
      await expect(page.locator(`input[name="unitCost_${f.country}"]`)).toHaveValue(String(f.unitCost));
    }
    await expect(page.locator('input[name="shipCount"]')).toHaveValue(String(original.shipCount));
    await expect(page.locator('input[name="shipDailyCost"]')).toHaveValue(
      String(original.shipDailyCostWon / 100_000_000),
    );
    for (const r of original.routes) {
      await expect(page.locator(`input[name="days_${r.from}_${r.to}"]`)).toHaveValue(String(r.days));
    }

    // 공장·선박·항로 표가 있고, 확정 주문은 바뀌지 않는다는 안내가 있다
    await expect(page.getByRole('heading', { name: '공장' })).toBeVisible();
    await expect(page.getByRole('heading', { name: '선박' })).toBeVisible();
    await expect(page.getByRole('heading', { name: /항로/ })).toBeVisible();
    await expect(page.getByText('값을 바꿔도 이미 확정한 주문의 계획은 바뀌지 않습니다')).toBeVisible();
  });

  /** E2E-037 값을 바꿔 저장하면 유지되고 버전이 오른다 */
  test('E2E-037 값을 바꿔 저장하면 유지되고 버전이 오른다', async ({ page }) => {
    const china = original.factories.find((f: any) => f.country === 'CHINA');
    const newMaxDaily = china.maxDaily + 100; // 예: 400 → 500

    // 1. 저장 전 version 기록
    const versionBefore = original.version;

    // 2. 중국 하루 최대 생산량을 바꿔 [저장]
    await page.goto('/master');
    await page.locator('input[name="maxDaily_CHINA"]').fill(String(newMaxDaily));
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page).toHaveURL(/\/master$/);
    await expect(page.locator('.alert.ok')).toHaveText('마스터 데이터를 저장했습니다');

    // 3. 화면을 새로 고쳐도 값이 남아 있고, API 의 version 이 1 올라 있다
    await page.reload();
    await expect(page.locator('input[name="maxDaily_CHINA"]')).toHaveValue(String(newMaxDaily));
    const after = await apiMaster(page);
    expect(after.factories.find((f: any) => f.country === 'CHINA').maxDaily).toBe(newMaxDaily);
    expect(after.version).toBe(versionBefore + 1);

    // 4. 정리(원복)는 afterEach 에서 한다
  });

  /** 기본 수량으로 시뮬레이션을 실행하고 '운항비 최소' 안의 소요 일수와 화면의 계획용 마스터 표를 돌려준다. */
  async function simulateDefault(page: import('@playwright/test').Page) {
    await page.goto('/orders/new');
    await page.getByRole('button', { name: '시뮬레이션 실행' }).click();
    await expect(page.locator('#options .option')).toHaveCount(3);
    await expect(page.locator('#options .option').first()).toHaveClass(/selected/); // 운항비 최소
    const daysText = await page
      .locator('#summary .stats div', { hasText: '소요 일수' })
      .locator('b')
      .innerText();
    return Number(daysText.replace('일', '').trim());
  }

  /** E2E-038 바뀐 마스터 값이 새 시뮬레이션에 반영된다 */
  test('E2E-038 바뀐 마스터 값이 새 시뮬레이션에 반영된다', async ({ page }) => {
    const china = original.factories.find((f: any) => f.country === 'CHINA');

    // 1. 기본 수량으로 시뮬레이션해 '운항비 최소'의 소요 일수를 기록한다
    const daysBefore = await simulateDefault(page);

    // 2. 마스터에서 중국 하루 최대 생산량을 크게 줄여(예: 400 → 100) 저장한다
    await page.goto('/master');
    await page.locator('input[name="maxDaily_CHINA"]').fill('100');
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.locator('.alert.ok')).toHaveText('마스터 데이터를 저장했습니다');

    // 3. 같은 수량으로 다시 시뮬레이션한다: 소요 일수가 늘어나거나 같고, 계획에 쓴 마스터 값에 100 이 보인다
    const daysAfter = await simulateDefault(page);
    expect(daysAfter).toBeGreaterThanOrEqual(daysBefore);
    expect(china.maxDaily).not.toBe(100); // 값이 실제로 바뀐 상황인지 확인

    const masterDetails = page.locator('#details details', { hasText: '하루 최대(K)' }); // '계획에 쓴 마스터 값' 펼치기
    await masterDetails.locator('summary').click();
    const chinaRow = masterDetails.locator('table', { hasText: '하루 최대(K)' }).locator('tr', { hasText: '중국' });
    await expect(chinaRow.locator('td.num').first()).toHaveText('100');

    // 4. 정리(원복)는 afterEach 에서 한다
  });

  /** E2E-039 잘못된 운항비(숫자 아님·0·원 단위 아님)는 저장되지 않는다 */
  test.describe('E2E-039 잘못된 운항비는 저장되지 않는다', () => {
    const cases = [
      { name: '숫자가 아님', value: 'abc', message: '척당 일 운항비(억 원)는 숫자로 입력하세요' },
      { name: '0 이하', value: '0', message: '척당 일 운항비는 0보다 커야 합니다' },
      { name: '원 단위로 떨어지지 않음', value: '1.000000001', message: '척당 일 운항비는 원 단위로 떨어지는 값이어야 합니다' },
    ];

    for (const c of cases) {
      test(`E2E-039 운항비 ${c.name} (${c.value})`, async ({ page }) => {
        await page.goto('/master');
        const input = page.locator('input[name="shipDailyCost"]');
        const savedValue = await input.inputValue();
        await input.fill(c.value);
        await page.getByRole('button', { name: '저장' }).click();

        // 오류 안내가 보이고, 입력한 값은 그대로 남는다
        await expect(page.locator('.alert.error strong')).toHaveText('저장하지 않았습니다. 아래 값을 확인하세요.');
        await expect(page.locator('.alert.error li')).toHaveText([c.message]);
        await expect(input).toHaveValue(c.value);

        // 저장되지 않았다: 새로 고치면 이전 값이 보이고, 서버의 값과 version 도 그대로다
        await page.goto('/master');
        await expect(page.locator('input[name="shipDailyCost"]')).toHaveValue(savedValue);
        const m = await apiMaster(page);
        expect(m.shipDailyCostWon).toBe(original.shipDailyCostWon);
        expect(m.version).toBe(original.version);
      });
    }
  });

  /** E2E-040 잘못된 값이 섞이면 아무것도 저장되지 않고 오류가 모두 나온다 */
  test('E2E-040 잘못된 값이 섞이면 아무것도 저장되지 않고 오류가 모두 나온다', async ({ page }) => {
    const china = original.factories.find((f: any) => f.country === 'CHINA');
    const validMaxDaily = china.maxDaily + 100; // 올바른 값(예: 400 → 500)

    await page.goto('/master');
    // 숫자 칸의 min/max 속성(브라우저 기본 검증)이 먼저 막지 않도록 끈다
    await page.locator('form[action$="/master"]').evaluate((f: HTMLFormElement) => (f.noValidate = true));

    // 1. 올바른 값 하나 + 2. 잘못된 값 세 개(배 척수 5, 일본 재고 상한 0, 한국-베트남 항해 일수 400)
    await page.locator('input[name="maxDaily_CHINA"]').fill(String(validMaxDaily));
    await page.locator('input[name="shipCount"]').fill('5');
    await page.locator('input[name="stockLimit_JAPAN"]').fill('0');
    await page.locator('input[name="days_KOREA_VIETNAM"]').fill('400');

    // 3. 저장 → 오류 3건이 한꺼번에 나열된다
    await page.getByRole('button', { name: '저장' }).click();
    await expect(page.locator('.alert.error strong')).toHaveText('저장하지 않았습니다. 아래 값을 확인하세요.');
    await expect(page.locator('.alert.error li')).toHaveText([
      '배 척수은(는) 1 이상 4 이하의 정수여야 합니다',
      '일본 재고 상한은(는) 1 이상 1000000 이하의 정수여야 합니다',
      '한국-베트남 항해 일수은(는) 1 이상 365 이하의 정수여야 합니다',
    ]);

    // 4. 새로 고친다: 올바른 값(중국 하루 최대)도 저장되지 않아 원래 값이다
    await page.goto('/master');
    await expect(page.locator('input[name="maxDaily_CHINA"]')).toHaveValue(String(china.maxDaily));
    const m = await apiMaster(page);
    expect(m.factories.find((f: any) => f.country === 'CHINA').maxDaily).toBe(china.maxDaily);
    expect(m.shipCount).toBe(original.shipCount);
    expect(m.version).toBe(original.version);
  });
});

// ======================================================================
// E2E-041
// ======================================================================
/**
 * E2E-041 로그인부터 완료·로그아웃까지 한 번에 진행한다 (대표 스모크 테스트)
 *
 * 로그인 → 주문 추가 → 시뮬레이션 → '운항비 최소' 선택·메모·확정 → 진행 → 완료
 * → 목록의 '완료' 필터 → 로그아웃. 화면만 사용해 끝까지 이어지는지 본다.
 *
 * 주문이 1건 생기고 '완료' 상태라 취소할 수 없다. (테스트 DB 에 남는다)
 * 건수는 다른 테스트가 동시에 주문을 만들 수 있어 "늘었다(>=)"로 확인한다.
 */
test('E2E-041 로그인부터 완료·로그아웃까지 한 번에 진행한다', async ({ page }) => {
  // 1. 로그인 (화면으로)
  await loginUi(page);
  await expect(page).toHaveURL(/\/orders$/);
  const completedBefore = await chipCount(page, '완료');

  // 2. [주문 추가]에서 기본 수량으로 시뮬레이션
  await page.locator('header').getByRole('link', { name: '주문 추가' }).click();
  await expect(page).toHaveURL(/\/orders\/new$/);
  await page.getByRole('button', { name: '시뮬레이션 실행' }).click();
  await expect(page.locator('#options .option')).toHaveCount(3);

  // 3. '운항비 최소'를 선택하고 메모를 적어 [주문 확정] → 상세 화면, 상태 '확정'
  // (다른 테스트가 그 사이 마스터를 바꿔 확정이 거부되면 다시 시뮬레이션해서 확정한다. 최대 3번)
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.locator('#options .option', { hasText: '운항비 최소' }).click();
    await expect(page.locator('#summary')).toContainText('운항비 최소');
    await page.locator('#memo').fill('E2E-041 종단 흐름');
    await page.getByRole('button', { name: '주문 확정' }).click();
    const moved = await page
      .waitForURL(/\/orders\/\d+$/, { timeout: 3000 })
      .then(() => true)
      .catch(() => false);
    if (moved) break;
    await expect(page.locator('#confirmError')).toContainText('마스터 데이터가 바뀌었습니다');
    await page.getByRole('button', { name: '시뮬레이션 실행' }).click();
    await expect(page.locator('#options .option')).toHaveCount(3);
  }
  await expect(page).toHaveURL(/\/orders\/\d+$/);
  const orderId = Number(page.url().match(/\/orders\/(\d+)$/)![1]);
  await expect(page.locator('h1 .badge')).toHaveText('확정');

  // 4. '진행' → '완료'로 차례로 바꾼다 → 이력 3행, 더 바꿀 수 없다
  await changeStatusByButton(page, '진행');
  await expect(page.locator('h1 .badge')).toHaveText('진행');
  await changeStatusByButton(page, '완료');
  await expect(page.locator('h1 .badge')).toHaveText('완료');
  expect(await readHistory(page)).toEqual(['- → 확정', '확정 → 진행', '진행 → 완료']);
  await expect(page.getByText('최종 상태라 더 바꿀 수 없습니다.')).toBeVisible();
  await expect(page.getByRole('button', { name: /으\)로 변경/ })).toHaveCount(0);

  // 5. 주문 목록에서 '완료' 칩 → 방금 주문이 보이고 '완료' 건수가 늘었다
  await page.getByRole('link', { name: '목록으로' }).click();
  await page.locator('.filters .chip', { hasText: '완료' }).click();
  await expect(page).toHaveURL(/status=COMPLETED/);
  const row = page.locator('table tbody tr', { has: page.locator(`a[href="/orders/${orderId}"]`) });
  await expect(row).toBeVisible();
  await expect(row.locator('.badge')).toHaveText('완료');
  expect(await chipCount(page, '완료')).toBeGreaterThanOrEqual(completedBefore + 1);

  // 6. 로그아웃 → 로그인 화면, 다시 /orders 에 가도 로그인 화면
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await page.goto('/orders');
  await expect(page).toHaveURL(/\/login$/);
});
