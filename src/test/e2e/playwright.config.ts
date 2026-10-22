import { defineConfig } from '@playwright/test';
import path from 'node:path';

/**
 * battery E2E 테스트 (E2E-021 ~ E2E-030, 허주 담당)
 *
 * - 테스트가 앱을 직접 띄운다: 포트 8091, 메모리 DB. 개발 앱(8080)과 개발 DB를 건드리지 않는다.
 * - 시나리오가 주문을 만들고 마스터를 바꾸므로 한 번에 하나씩, 파일 이름 순서로 실행한다.
 * - 브라우저는 PC에 설치된 Chrome을 쓴다(별도 다운로드 없음). 기본은 headless 이고 화면을 보려면 --headed.
 * - 환경 변수 E2E_REPORT_DIR 이 있으면 그 폴더에 HTML 리포트와 JSON 결과를 만든다. (npm run test:report)
 */
const PORT = 8091;
const isWindows = process.platform === 'win32';
const repoRoot = path.resolve(__dirname, '..', '..', '..'); // 저장소 루트
const gradle = isWindows ? '.\\gradlew.bat' : './gradlew';
const appArgs = [
  `--server.port=${PORT}`,
  '--spring.datasource.url=jdbc:h2:mem:e2e-huju;DB_CLOSE_DELAY=-1',
  '--spring.jpa.hibernate.ddl-auto=create-drop',
  '--spring.devtools.restart.enabled=false',
  '--app.rate-limit.signup.max-requests=100000', // E2E 는 한 주소에서 가입·로그인을 많이 하므로 횟수 제한을 풀어 둔다
  '--app.rate-limit.login.max-requests=100000',
].join(' ');

const reportDir = process.env.E2E_REPORT_DIR;
const reporter: any = reportDir
  ? [
      ['list'],
      ['html', { outputFolder: path.join(reportDir, 'html'), open: 'never' }],
      ['json', { outputFile: path.join(reportDir, 'results.json') }],
    ]
  : [['list'], ['html', { open: 'never' }]];

export default defineConfig({
  testDir: '.',
  testMatch: /e2e-0(2[0-9]|30).spec.ts/, // 내 시나리오(021~030)만 실행한다
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  expect: { timeout: 5_000 },
  reporter,
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
