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

리뷰 코멘트 수는 PR 작성자 본인을 뺀 댓글과 리뷰의 합계입니다.

<br>

## Best Practice: 코드 리뷰

#### Best Practice 1. 위험이 큰 변경은 더 깊게 리뷰하고, 결정 이유를 남기기

| 항목 | 내용 |
|---|---|
| 구현 내용 | 회원 가입 후 로그인 (가입 회원은 admin과 같은 권한). 인증·권한이 바뀌는 🔴 Red 변경 |
| PR 링크 | [#28](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28) · Action 장영은 프로, Gate 정혜미 프로 |
| 문제 상황 | 누구나 가입해서 admin 권한을 얻는 변경이라 위험이 큼<br>처음에는 가입·로그인 횟수 제한이 없었고, 로그인 때 세션을 새로 만들지 않았으며, 고정 계정(`admin/admin`)이 남아 있었음 |
| 적용한 방법 | Gate가 변경 전체를 읽고, 직접 받아 테스트를 모두 돌려 봄<br>지적 8건을 심각도(높음·중간·낮음), 위치, 내용 표로 정리하고, 구현 전·구현·검증 단계별로 빠진 점도 점검<br>작성자는 고친 것과 고치지 않은 것을 이유와 함께 답함 (고정 계정 유지는 근거 3가지와 한계를 적어 재검토 요청) |
| 좋았던 점 | 위험이 큰 영역이라 더 깊게 리뷰하고, 지적을 표로 정리해 우선순위가 분명함<br>고친 내용(횟수 제한, 세션 재발급, 해시 설정 저장)을 테스트로 확인함. 세션 재발급을 끄면 E2E가 실패하는지까지 확인<br>고치지 않은 것도 이유가 남아 머지 판단의 근거가 됨 |
| Evidence | [Gate 보충 리뷰](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#pullrequestreview-5610215328) · [Gate 인증 코드·단계별 점검](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#issuecomment-6335811284) · [작성자 반영 내역](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#issuecomment-6335670043) · [작성자 결정 근거](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28#issuecomment-6336020776) · [report](../docs/evidence/youngeun/TASK-002-auth-hardening/report.html) |

#### Best Practice 2. 5,180가지 입력으로 수정 전·후 비교하기

| 항목 | 내용 |
|---|---|
| 구현 내용 | "가장 빠르게" 안이 "가까운 곳부터" 안보다 오래 걸릴 수 있던 버그 수정 |
| PR 링크 | [#27](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/27) · Action 정혜미 프로, Gate 허주 프로·장영은 프로 |
| 문제 상황 | 코드만 봐서는 "그 사례 하나만 고친 것인지" 알 수 없음 |
| 적용한 방법 | Gate가 배 1~4척 × 주문 조합 5,180가지를 수정 전·후로 같은 방법으로 비교<br>작성자는 같은 조합을 확인하는 테스트를 추가하고, 수정 전 코드에서는 실패하는 것을 확인<br>반영 내역에 누구의 몇 번째 지적인지 표시 |
| 좋았던 점 | "빠른 안이 더 느린" 경우가 **152건에서 0건**으로 줄어든 것을 숫자로 확인함<br>같은 조합을 테스트로 남겨 같은 버그가 다시 생기는 것을 막음<br>지적별 반영 여부를 추적할 수 있음 |
| Evidence | [Gate 리뷰(5,180가지 비교)](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/27#pullrequestreview-5609327324) · [작성자 반영 안내](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/27#issuecomment-6332906871) · [버그 리포트](../docs/bug_report_bc51583_20261022_E2E-013b.html) |

#### Best Practice 3. 직접 측정한 숫자로 제안하고, 반영 여부를 이유와 함께 남기기

| 항목 | 내용 |
|---|---|
| 구현 내용 | 필요 납기일을 주문에 저장하고, 목록·상세에 납기 초과를 표시. 서버 검증과 DB 컬럼이 바뀌는 🟡 Yellow 변경 |
| PR 링크 | [#30](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30) · Action 정혜미 프로, Gate 전예리 프로 |
| 문제 상황 | 납기를 5~50일로 제한했는데, 큰 주문은 소요 일수가 50일을 넘어 모든 안이 "납기 초과"가 됨<br>납기 기능 이전에 만든 주문도 "가능한 빨리"로 보여 구별할 수 없음 |
| 적용한 방법 | Gate가 주문 크기별 소요 일수를 직접 재서 표로 제안 (12,000K는 20일, 32,000K는 45~49일, 60,000K는 80~84일, 100,000K는 130일)<br>서버 검증을 실제 요청으로 확인 (5일·50일은 통과, 4일·51일·5.5일 등은 거부)<br>직접 확인한 것과 확인하지 못한 것을 나눠 적음<br>작성자는 제안마다 반영 여부와 이유를 표로 답함 (요청 스펙이 "5~50일"이라 반영하지 않음) |
| 좋았던 점 | 말이 아니라 측정한 숫자라서 문제의 크기를 바로 이해함<br>확인한 범위가 분명해서 리뷰를 믿을 수 있음<br>반영하지 않은 결정도 이유가 남아, 스펙이 바뀔 때 다시 논의할 수 있음 (납기 상한 문제는 남은 과제) |
| Evidence | [Gate 리뷰(측정 표)](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30#issuecomment-6336912289) · [작성자 반영 여부 답변](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30#issuecomment-6337168326) |

---

[← 이전: 기능 추가 미션 동작 영상](03-missions.md) · [목차](README.md) · [다음: 회고 →](05-retrospective.md)
