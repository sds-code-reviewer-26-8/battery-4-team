# 4. PR & 코드리뷰

Action과 Gate 규칙을 적용하여 PR & 코드 리뷰를 진행했습니다.

기능 추가를 담당한 팀원은 Action의 역할로서 기능을 개발하는 동시에 Code Smell을 추가했습니다.

다른 팀원은 Gate 역할로서 코드 리뷰 활동을 하며 Agent와 함께 개선안을 도출하는 프로세스로 진행했습니다.

미션별 PR 현황을 정리하고, Best Practice를 선정하여 좋았던 점을 적어 봅니다.

<br>

## 미션별 PR 현황

| 미션 | Action 담당자 | Gate 담당자 | PR 링크 | 리뷰 코멘트 수 | Commit 수 |
|---|---|---|---|:-:|:-:|
| 기능 1. 고객사 | 허주 프로 | 장영은 프로 | [#29](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/29) | 4개 | 6개 |
| 기능 2. 필요 납기일 | 정혜미 프로 | 전예리 프로 | [#30](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30) | 2개 | 9개 |
| 기능 3. 회원 가입 | 장영은 프로 | 정혜미 프로 | [#28](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28) | 4개 | 5개 |
| 기능 4. 공장 운영비 | 전예리 프로 | 허주 프로 | [#31](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/31) | 2개 | 7개 |

- 리뷰 코멘트 수 : PR 작성자 본인을 제외한 댓글과 리뷰(Comment·Approve)의 합계 (2026-10-23 기준)
- 기능 4개 모두 main에 머지 완료

<br>

## Best Practice: 코드 리뷰

#### Best Practice 1. 위험 영역(Red)에 맞춘 깊은 리뷰와 결정 근거 남기기

| 항목 | 내용 |
|---|---|
| 구현 내용 | 회원 가입 후 로그인, 가입 회원은 admin과 같은 권한 (🔴 Red: 인증·권한 변경) |
| PR 링크 | [#28](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28) · Action 장영은 프로, Gate 정혜미 프로 |
| 문제 상황 | 공개 가입으로 누구나 admin 권한을 얻는 변경<br>처음에는 가입·로그인 횟수 제한 없음, 로그인 때 세션 ID 미재발급(세션 고정 위험), `admin/admin` 고정 계정이 남아 있었음 |
| 적용한 방법 | Gate가 diff 전체(15개 파일)를 읽고 `feature/signup`을 직접 받아 JUnit·E2E 전체를 실행<br>보충 리뷰에서 인증 코드 8건을 **심각도(높음·중간·낮음), 위치, 내용 표**로 정리하고, Prepare·Implement·Verify 단계별로 점검 (예: 위험 '높음'인데 HITL 없이 GO, PRD 10장 미확인, 전체 회귀 미실행)<br>"머지 전에 정하면 좋은 것"으로 마무리<br>작성자는 **반영 내역(4건, 커밋 2개)** 과 미반영·미확인 항목을 근거와 함께 답하고, `admin/admin` 유지는 근거 3가지와 한계를 적어 재검토 요청 |
| 좋았던 점 | 영역(Red)에 맞는 깊이로 리뷰하고, 지적을 표로 정리해 우선순위가 분명함<br>작성자가 고친 것(IP별 가입 시간당 10회·로그인 분당 60회 제한, 로그인 성공 시 세션 ID 재발급, 해시 설정 회원별 저장)을 테스트로 확인하고 `changeSessionId()`를 끄면 E2E가 실패하는지까지 확인<br>고치지 않은 것과 한계도 이유와 함께 공개해서 머지 판단 근거가 남음 |
| Evidence | [Gate 보충 리뷰](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#pullrequestreview-5610215328) · [Gate 인증 코드·Inner Loop 점검](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#issuecomment-6335811284) · [작성자 반영 내역](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#issuecomment-6335670043) · [작성자 결정 근거](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#issuecomment-6336020776) · [report](../docs/evidence/youngeun/TASK-002-auth-hardening/report.html) |

#### Best Practice 2. 5,180가지 입력으로 수정 전·후 비교하기

| 항목 | 내용 |
|---|---|
| 구현 내용 | "가장 빠르게" 안이 "가까운 곳부터" 안보다 오래 걸릴 수 있던 버그 수정 (E2E-013b) |
| PR 링크 | [#27](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/27) · Action 정혜미 프로, Gate 허주 프로·장영은 프로 |
| 문제 상황 | 수정한 코드가 "그 사례 하나만 고친 것인지" 코드만 봐서는 알 수 없음 |
| 적용한 방법 | Gate(허주 프로)가 임시 JUnit으로 **배 1~4척 × 주문 조합 5,180가지**를 main과 PR에서 같은 방법으로 비교 (임시 테스트는 커밋하지 않음)<br>작성자는 재발 방지 테스트 2건을 추가하고, 수정 전 코드로 되돌려 둘 다 실패하는 것을 확인<br>반영 내역에 "(youngeun9110 ①, ju-huh ①)"처럼 **누구의 몇 번째 지적인지** 표시 |
| 좋았던 점 | "빠른 안이 더 느린" 경우가 **152건 → 0건**이라는 숫자로 효과를 확인하고 다른 규칙 위반이 없음도 확인<br>같은 조합을 영구 테스트(속성 테스트)로 남겨 재발 방지<br>제안별 반영 여부가 추적됨. 작성자가 "전후 전체 비교는 따로 하지 않고 속성 테스트로 대신했다"고 솔직하게 적음 |
| Evidence | [Gate 리뷰(5,180가지 비교)](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/27#pullrequestreview-5609327324) · [작성자 반영 안내](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/27#issuecomment-6332906871) · [버그 리포트](../docs/bug_report_bc51583_20261022_E2E-013b.html) |

#### Best Practice 3. 직접 측정한 숫자로 제안하고, 반영 여부를 이유와 함께 남기기

| 항목 | 내용 |
|---|---|
| 구현 내용 | 필요 납기일을 확정할 때 주문에 저장하고, 목록·상세에 납기 초과를 표시 (🟡 Yellow: 서버 검증·DB 컬럼·API 변경) |
| PR 링크 | [#30](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30) · Action 정혜미 프로, Gate 전예리 프로 |
| 문제 상황 | 납기를 5~50일로 제한했는데, 큰 주문은 소요 일수가 50일을 넘어 모든 안이 "납기 초과"가 됨<br>납기 도입 전 주문도 모두 "가능한 빨리"로 보여 구별할 수 없음 |
| 적용한 방법 | Gate가 로컬에서 `/api/simulate`로 주문 크기별 소요 일수를 측정한 **표**로 제안 (12,000K는 20일, 32,000K는 45~49일, 60,000K는 80~84일, 100,000K는 130일)<br>서버 검증을 실제 요청 12종으로 확인하고(5·50은 200, 4·51·5.5·`"10"`·`true` 등은 400), 개발 DB 복사본으로 컬럼 추가를 확인<br>**확인 범위**에 직접 확인한 것과 믿고 넘어간 것(테스트 수는 다시 돌리지 않음)을 구분해 적음<br>작성자는 제안 3건 모두 "반영하지 않음"과 이유(요청 스펙이 "5~50일", 스펙이 바뀌면 다시 정함)를 표로 답한 뒤 승인 |
| 좋았던 점 | 말이 아니라 측정한 숫자로 제안해서 작성자와 팀이 문제 크기를 바로 이해함<br>직접 확인한 것과 확인하지 못한 것을 나눠 적어 리뷰의 신뢰도가 높음<br>반영하지 않은 결정도 이유가 남아, 스펙이 바뀔 때 다시 논의할 근거가 됨 (납기 상한 문제는 이번 PR에서 고치지 않고 남겨 둠) |
| Evidence | [Gate 리뷰(측정 표)](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30#issuecomment-6336912289) · [작성자 반영 여부 답변](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30#issuecomment-6337168326) |

---

[← 이전: 기능 추가 미션 동작 영상](03-missions.md) · [목차](README.md) · [다음: 회고 →](05-retrospective.md)
