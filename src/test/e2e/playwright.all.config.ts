import { defineConfig } from '@playwright/test';
import path from 'node:path';

/**
 * battery 전체 E2E (E2E-001 ~ E2E-041, 모든 *.spec.ts)
 *
 * - 테스트가 앱을 직접 띄운다: 포트 8092, 메모리 DB. 개발 앱(8080)과 개발 DB(./data/battery)를 건드리지 않는다.
 * - 시나리오가 주문을 만들고 마스터를 바꾸므로 한 번에 하나씩, 파일 이름 순서로 실행한다.
 * - 브라우저는 PC에 설치된 Chrome을 쓴다. 기본은 headless 이고 화면을 보려면 --headed.
 * - 실행: npx playwright test -c src/test/e2e/playwright.all.config.ts
 * - test.fail() 로 표시한 알려진 결함(현재는 E2E-035)은 "예상 실패"로 집계된다.
 */
const PORT = 8092;
const isWindows = process.platform === 'win32';
const repoRoot = path.resolve(__dirname, '..', '..', '..'); // 저장소 루트
const gradle = isWindows ? '.\\gradlew.bat' : './gradlew';
const appArgs = [
  `--server.port=${PORT}`,
  '--spring.datasource.url=jdbc:h2:mem:e2e-all;DB_CLOSE_DELAY=-1',
  '--spring.jpa.hibernate.ddl-auto=create-drop',
  '--spring.devtools.restart.enabled=false',
  '--app.rate-limit.signup.max-requests=100000', // E2E 는 한 주소에서 가입·로그인을 많이 하므로 횟수 제한을 풀어 둔다
  '--app.rate-limit.login.max-requests=100000',
].join(' ');

export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  reporter: [['list']],
  outputDir: path.join(repoRoot, 'test-results', 'e2e-all'),
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    channel: 'chrome',
    headless: true,
    locale: 'ko-KR',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: `${gradle} bootRun --args="${appArgs}"`,
    cwd: repoRoot,
    url: `http://127.0.0.1:${PORT}/login`,
    reuseExistingServer: false,
    timeout: 240_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
