# 1. 테스트 세팅

| 테스트 | 개수 | 테스트 도구 |
|---|:-:|---|
| Unit Test | 21개 | JUnit 5 (Gradle) |
| Slice Test | 0개 | - |
| 통합 테스트 | 43개 | JUnit 5 + Spring Boot Test (`@SpringBootTest`) |
| E2E 테스트 | 83개 | Playwright (`@playwright/test`) |
| Robustness 테스트 | 1개 | JUnit 5 (배 1~4척 × 주문 조합 전수 시험) |
| Stress 테스트 | 0개 | - |
| 성능 테스트 | 0개 | - |
| Test Coverage | 측정 안 함 | - |

- JUnit 전체 65건 = Unit 21 + 통합 43 + Robustness 1 (main `9745262` 기준, 2026-10-23 직접 실행, 모두 통과)
- E2E 83건 실행, 모두 통과로 집계. 그중 1건(E2E-035)은 알려진 결함이라 "실패해야 통과"로 표시한 예상 실패
- 시나리오 엑셀은 42개(기능 26, 견고성 16)이고, 이를 포함해 이후 추가된 기능의 시험까지 Playwright 83건으로 구현

<br>

## 테스트 시나리오

E2E 시나리오 엑셀(`docs/e2e/e2e-test-scenarios-final.xlsx`)의 앞 10개입니다. 전체 42개는 엑셀에서 볼 수 있습니다.

![테스트 시나리오](assets/test-scenarios.png)

---

[← 목차](README.md) · [다음: Loop 설계 →](02-loop.md)
