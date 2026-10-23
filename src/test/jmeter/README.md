# JMeter 스트레스 테스트

`battery-stress.jmx`는 **stress 프로필로 띄운 앱**에만 쓴다. 개발 앱(8080)과 개발 DB에는 쓰지 않는다.

## 1. 앱 띄우기

```
./gradlew bootRun --args='--server.port=8081 --spring.datasource.url=jdbc:h2:mem:stress --spring.profiles.active=stress --spring.devtools.restart.enabled=false --app.rate-limit.login.max-requests=100000'
```

- `stress` 프로필에서만 `/api/stress/*`(health, simulate, orders 생성·삭제)가 만들어지고 로그인 없이 열린다.
- 로그인 한도(분당 60회)를 올리지 않으면 로그인 단계가 429로 막힌다. 설정 파일은 바꾸지 않고 실행 인자로만 올린다.
- 재시작(devtools)이 켜져 있으면 부하 중에 연결이 끊길 수 있어 끈다.

## 2. 실행 (CLI 모드, GUI는 부하를 못 낸다)

```
jmeter -n -t src/test/jmeter/battery-stress.jmx -Jthreads=50 -JloginThreads=10 -JwriteThreads=10 -Jrampup=30 -Jduration=120 -l result.jtl -e -o report
```

| 속성 | 기본값 | 뜻 |
|---|---|---|
| `host`, `port` | localhost, 8081 | 대상 앱 |
| `threads` | 10 | A 그룹(무로그인 계산·health) 동시 사용자 |
| `loginThreads` | 5 | B 그룹(로그인 후 목록·상세) |
| `writeThreads` | 5 | C 그룹(주문 확정) |
| `rampup`, `duration` | 10, 60 | 사용자를 늘리는 시간, 전체 실행 시간(초). `duration`은 `rampup`을 포함한다(120초로 주면 처음 30초 동안 늘리고 총 120초 실행). 반복 횟수(Loop Count)는 무한이고 시간으로 멈춘다 |
| `seedCount` | 50 | 시작 전에 만들어 두는 주문 수 |
| `think` | 500 | A·B 그룹의 쉬는 타이머 값(ms). 타이머 2개가 요청마다 더해져 요청 사이에 `2×think ~ 4×think` ms 쉰다(기본 1~2초, 평균 1.5초). 0이면 쉬지 않는다 |
| `writeThink` | 1000 | C 그룹의 쉬는 타이머 값(ms). 요청마다 `writeThink ~ 2×writeThink` ms 쉰다(기본 1~2초, 평균 1.5초). 0이면 쉬지 않는다 |

`result.jtl`과 `report/`는 저장소에 올리지 않는다.

## 3. 시나리오

- **setUp:** 시뮬레이션으로 `master.version`을 얻은 뒤, 그 버전으로 읽기용 주문을 `seedCount`건 만든다.
- **A 읽기(무로그인):** `POST /api/stress/simulate`(계산), `GET /api/stress/health`
- **B 읽기(로그인):** `/api/login`(스레드당 한 번) → `GET /api/orders`, `GET /api/orders/{id}`
- **C 쓰기:** 시뮬레이션으로 `master.version`을 얻고 → `POST /api/stress/orders`(그 버전으로 1건 확정: 서버 재계산 + 저장). 실제 화면의 시뮬레이션 → 확정 흐름과 같다.
- **tearDown:** 작성자가 `stress`인 주문을 취소해 지운다(주문은 삭제되고 상태 이력은 남는다). 사람이 만든 주문은 건드리지 않는다.

모든 요청에 HTTP 200 단언이 있다.

`POST /api/stress/orders`는 `masterVersion`이 필수이고 현재 마스터와 다르면 409로 거부한다(INTENT 원칙 3).
이 계획은 마스터를 바꾸지 않으므로 409는 나오지 않는다. 시험 중에 마스터를 저장하면 C 그룹이 409로 실패한다.
