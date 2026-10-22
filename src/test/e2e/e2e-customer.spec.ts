import { expect, test, type Page } from '@playwright/test';
import { changeStatusUi, meta, openNewOrder, pickOption, runSimulation } from './helpers';
import { listRow, login, normalize, simulate } from './common-helpers';

// 고객사 이름 입력·수정 (PDF 추가 미션 4 · 기능 추가 1)
// 주문 확정 때 고객사를 받을 수 있고(선택), 확정·진행 상태에서는 작성자가 고칠 수 있다. 완료·삭제 뒤에는 고칠 수 없다.

const BASE_ORDERS = { CHINA: 3000, JAPAN: 3000, VIETNAM: 3000, INDONESIA: 3000 };

/** 고객사 이름을 담아 API 로 확정 요청을 보낸다. 응답을 그대로 돌려준다(오류 확인용). */
async function postOrder(page: Page, customerName: string | null | undefined) {
  const sim = await simulate(page.request);
  return page.request.post('/api/orders', {
    data: {
      orders: BASE_ORDERS,
      routeType: 'COST',
      masterVersion: sim.master.version,
      memo: 'E2E-customer',
      ...(customerName === undefined ? {} : { customerName }),
    },
  });
}

/** 고객사 이름을 담아 API 로 확정하고 주문 번호를 돌려준다. */
async function confirmWithCustomer(page: Page, customerName: string): Promise<number> {
  const res = await postOrder(page, customerName);
  expect(res.status(), await res.text()).toBe(200);
  return ((await res.json()) as { id: number }).id;
}

/** 목록의 맨 끝(9번째) 열 값 */
async function listCustomerCell(page: Page, id: number): Promise<string> {
  await page.goto('/orders');
  // 행은 목록 API 응답 뒤에 그려진다. allInnerTexts() 는 기다리지 않으므로 먼저 행이 보일 때까지 기다린다.
  await expect(listRow(page, id)).toBeVisible();
  const cells = await listRow(page, id).locator('td').allInnerTexts();
  expect(cells).toHaveLength(9);
  return normalize(cells[8]);
}

test.describe('고객사 이름', () => {
  test('화면에서 고객사를 입력해 확정하면 상세·목록에 보이고, 상세에서 수정할 수 있다', async ({ page }) => {
    await login(page);

    // 주문 추가 화면의 3번 카드에서 고객사를 입력하고 확정한다(앞뒤 공백은 서버가 지운다)
    await openNewOrder(page);
    await runSimulation(page);
    await pickOption(page, 'COST');
    await expect(page.locator('#customerName')).toHaveAttribute('maxlength', '100');
    await page.locator('#customerName').fill('  DSD 모터스  ');
    await page.locator('#confirmBtn').click();
    await expect(page).toHaveURL(/\/orders\/\d+$/);
    const id = Number(page.url().split('/').pop());

    // 상세: 고객사 항목과 수정 입력란
    await expect(meta(page, '고객사')).toHaveText('DSD 모터스');
    await expect(page.locator('.customer-form')).toBeVisible();
    await expect(page.locator('#customerInput')).toHaveValue('DSD 모터스');

    // 목록: 맨 끝 열
    expect(await listCustomerCell(page, id)).toBe('DSD 모터스');

    // 수정: 저장하면 안내 문구가 보이고, 다시 읽은 값과 새로고침 뒤 값이 바뀐다
    await page.goto(`/orders/${id}`);
    await page.locator('#customerInput').fill('새 고객사');
    await page.getByRole('button', { name: '고객사 저장' }).click();
    await expect(page.locator('.alert.ok')).toContainText('고객사 이름을 저장했습니다');
    await expect(meta(page, '고객사')).toHaveText('새 고객사');
    await page.reload();
    await expect(meta(page, '고객사')).toHaveText('새 고객사');
    expect(await listCustomerCell(page, id)).toBe('새 고객사');

    // 진행 상태에서도 수정할 수 있다
    await changeStatusUi(page, id, 'IN_PROGRESS');
    await expect(page.locator('h1 .badge')).toHaveText('진행');
    await expect(page.locator('.customer-form')).toBeVisible();
    await page.locator('#customerInput').fill('진행 중 고객사');
    await page.getByRole('button', { name: '고객사 저장' }).click();
    await expect(page.locator('.alert.ok')).toContainText('고객사 이름을 저장했습니다');
    await expect(meta(page, '고객사')).toHaveText('진행 중 고객사');

    // 비우고 저장하면 "-" 로 보인다
    await page.locator('#customerInput').fill('');
    await page.getByRole('button', { name: '고객사 저장' }).click();
    await expect(page.locator('.alert.ok')).toContainText('고객사 이름을 저장했습니다');
    await expect(meta(page, '고객사')).toHaveText('-');
    expect(await listCustomerCell(page, id)).toBe('-');
  });

  test('입력하지 않으면 "-" 로 보이고, 101자는 거부되며 100자는 허용된다', async ({ page }) => {
    await login(page);

    // 화면에서 고객사를 비운 채 확정해도 통과한다
    await openNewOrder(page);
    await runSimulation(page);
    await pickOption(page, 'COST');
    await page.locator('#confirmBtn').click();
    await expect(page).toHaveURL(/\/orders\/\d+$/);
    const id = Number(page.url().split('/').pop());
    await expect(meta(page, '고객사')).toHaveText('-');
    expect(await listCustomerCell(page, id)).toBe('-');

    // 상세에서 101자로 저장하려 하면 이유만 보이고 입력값은 그대로 남는다
    await page.goto(`/orders/${id}`);
    const long101 = '가'.repeat(101);
    await page.locator('#customerInput').evaluate((el) => el.removeAttribute('maxlength')); // 화면 제한을 풀어 서버 검사를 확인
    await page.locator('#customerInput').fill(long101);
    await page.getByRole('button', { name: '고객사 저장' }).click();
    await expect(page.locator('.alert.error')).toContainText('고객사 이름은 100자 이하로 입력하세요');
    await expect(page.locator('#customerInput')).toHaveValue(long101);
    await expect(meta(page, '고객사')).toHaveText('-');

    // API 로 직접 101자를 보내면 400 과 이유
    const res = await postOrder(page, long101);
    expect(res.status()).toBe(400);
    expect(((await res.json()) as { error: string }).error).toBe('고객사 이름은 100자 이하로 입력하세요');

    // 정확히 100자는 허용된다
    const ok = await postOrder(page, '나'.repeat(100));
    expect(ok.status(), await ok.text()).toBe(200);
  });

  test('완료 상태에서는 수정 입력란이 없고 API 로 직접 보내도 거부된다', async ({ page }) => {
    await login(page);
    const id = await confirmWithCustomer(page, '완료 전 고객사');
    await changeStatusUi(page, id, 'IN_PROGRESS');
    await changeStatusUi(page, id, 'COMPLETED');

    await page.goto(`/orders/${id}`);
    await expect(page.locator('h1 .badge')).toHaveText('완료');
    await expect(meta(page, '고객사')).toHaveText('완료 전 고객사');
    await expect(page.locator('.customer-form')).toHaveCount(0);
    await expect(page.locator('#customerInput')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '고객사 저장' })).toHaveCount(0);

    // API 직접 호출도 거부
    const detail = await page.request.get(`/api/orders/${id}`);
    const { version, customerEditable } = (await detail.json()) as { version: number; customerEditable: boolean };
    expect(customerEditable).toBe(false);
    const res = await page.request.post(`/api/orders/${id}/customer`, { data: { customerName: '완료 뒤 변경', version } });
    expect(res.status()).toBe(400);
    const body = (await res.json()) as { error: string; conflict: boolean };
    expect(body.error).toBe('완료 상태의 주문은 고객사를 수정할 수 없습니다');
    expect(body.conflict).toBe(false);

    // 값은 그대로
    await page.reload();
    await expect(meta(page, '고객사')).toHaveText('완료 전 고객사');
  });

  test('HTML 문자가 들어 있어도 목록·상세에 글자 그대로 보인다', async ({ page }) => {
    await login(page);
    const name = '<b>x</b>';
    const id = await confirmWithCustomer(page, name);

    // 목록
    expect(await listCustomerCell(page, id)).toBe(name);
    await expect(listRow(page, id).locator('td b')).toHaveCount(0);

    // 상세
    await page.goto(`/orders/${id}`);
    await expect(meta(page, '고객사')).toHaveText(name);
    await expect(meta(page, '고객사').locator('b')).toHaveCount(0);
    await expect(page.locator('#customerInput')).toHaveValue(name);

    // 수정으로 넣어도 마찬가지
    await page.locator('#customerInput').fill('<i>y</i>&amp;');
    await page.getByRole('button', { name: '고객사 저장' }).click();
    await expect(page.locator('.alert.ok')).toContainText('고객사 이름을 저장했습니다');
    await expect(meta(page, '고객사')).toHaveText('<i>y</i>&amp;');
    await expect(meta(page, '고객사').locator('i')).toHaveCount(0);
    expect(await listCustomerCell(page, id)).toBe('<i>y</i>&amp;');
  });
});
