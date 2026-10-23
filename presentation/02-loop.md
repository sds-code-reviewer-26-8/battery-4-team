# 2. Loop 설계

Loop 설계는 이번 팀 프로젝트에서 가장 핵심이 되는 내용입니다.

이번 프로젝트에서는 구현 전에 점검하고, 구현 후에는 검증하여 리포트를 작성하는 자신만의 Loop를 각자 설계했습니다.

이 중 소개할 만한 좋은 Loop를 선정하여 소개합니다.

<br>

## 팀 Evidence 템플릿

팀에서 협의한 템플릿 2개입니다. 모든 Loop가 같은 틀로 구현 전·후 증거를 남깁니다.

| 템플릿 | 사용 시점 | 링크 |
|---|---|---|
| precheck.html | 구현 전 | [precheck.template.html](../docs/templates/precheck.template.html) |
| report.html | 구현 후 | [report.template.html](../docs/templates/report.template.html) |

GitHub에서는 `.html` 파일이 소스로 보여서, 실제 사용 예(허주 프로, 고객사 이름 입력 기능)를 캡처했습니다.

| precheck.html (구현 전 점검) | report.html (구현 결과 보고) |
|---|---|
| ![precheck.html 캡처](assets/evidence-precheck.png) | ![report.html 캡처](assets/evidence-report.png) |
| 원본: [precheck.html](../docs/evidence/허주/TASK-001-customer-name/precheck.html) | 원본: [report.html](../docs/evidence/허주/TASK-001-customer-name/report.html) |

<br>

## Best Practice: Loop 설계

팀 프로젝트 중 각자 자신만의 Loop를 소개한 Markdown 페이지를 업로드했습니다. (경로 : `docs/loops/팀원이름/README.md`)

이 중 괜찮다고 생각이 드는 Best Practice Loop를 소개합니다.

#### Best Practice 1

| 항목 | 내용 |
|---|---|
| 소개 팀원 | 정혜미 프로 ("전후 사진 비교" 리팩토링 Loop) |
| Loop 설명 링크 | [docs/loops/정혜미/README.md](../docs/loops/정혜미/README.md) |
| 좋았던 점 | 구현 전에 "이 계획이 가능한가"(실현성 점검)와 "이득이 있는가"(효과 측정)를 먼저 따져 헛수고를 줄임<br>정리 전·후의 계산 결과를 같은 방법으로 찍어 글자 단위로 비교해 "동작이 그대로"를 증거로 남김<br>첫 실행에서 겪은 실패(불가능한 계획 승인, 같은 실패 3회 반복)를 설계에 반영해 고침 |

#### Best Practice 2

| 항목 | 내용 |
|---|---|
| 소개 팀원 | 전예리 프로 (버그수정 Loop) |
| Loop 설명 링크 | [docs/loops/yeri/README.md](../docs/loops/yeri/README.md) |
| 좋았던 점 | 수정 전에 먼저 버그를 재현하고 원인 위치를 찾으며, PRD·INTENT에 없는 요구는 사람에게 돌려보냄(HITL)<br>체크리스트 3개(재현 절차 / 재발 방지 테스트 / 기존 테스트 전체)로 "고쳤다"의 기준이 분명함<br>검증에 실패하면 실패 내용을 들고 점검 단계로 돌아가고, 최대 3회차에서 멈춤 |

<br>

## Loop를 돌리며 느꼈던 점

#### 좋았던 점

- 단계마다 새 대화로 시작하는 Node(Subagent) 덕분에, 앞 단계의 판단에 끌려가지 않고 서로 확인함 (고객사 기능: Node A가 PRD 10장 "확정 후 주문 수정 제외"와의 충돌을 스스로 발견)
- 구현 전 사람 확인(HITL ①)에서 선택 입력, 100자 제한, 작성자만 수정 등 요구를 먼저 정하고 시작해서 구현이 1회차에 통과함
- 구현 전·후 증거(precheck·report)와 로그가 남아 리뷰어가 근거를 바로 볼 수 있음

#### 아쉬웠던 점

- 처음에는 Loop의 단계만 흉내 내어 직접 진행했고, "Workflow로 돌려 달라"는 요청을 받고서야 처음부터 다시 함
- 로그 원본이 세션 임시 폴더에만 있어서, 팀원이 "출처가 불분명하다"고 짚음 (옮겨 적은 로그임을 문서에 밝힘)

#### 문제와 개선

| 문제 | 원인 | 어떻게 개선했나요? |
|---|---|---|
| 점검 결과에 "범위 밖 저촉 없음"이라고 잘못 씀 | PRD 10장(범위 밖)을 확인하지 않음 | 독립 Node(A)가 문서 충돌을 발견해 바로잡고, 문서를 코드보다 먼저 수정하도록 계획 |
| report의 커밋 표기가 틀림 | 커밋하기 전에 report를 작성 | 임시 표시 → 코드 커밋 → 해시 확정 → 문서 커밋 순서로 변경 |
| E2E 시험이 가끔 실패함 | 목록 행이 그려지기 전에 값을 읽음 | 응답을 500ms 늦춰 실패를 재현 → 대기 추가 → 같은 조건 3/3, 10회 반복 40/40 통과로 확인 |
| 구현이 막혔는데 같은 시도를 3번 반복함 (정혜미 프로 REFACTOR-001) | 언어 규칙상 불가능한 계획을 승인하고, 이득도 재지 않음 | 실현성 점검·효과 측정을 구현 전에 추가하고, 같은 실패 2번이면 바로 사람에게 넘김 |

---

[← 이전: 테스트 세팅](01-test.md) · [목차](README.md) · [다음: 기능 추가 미션 동작 영상 →](03-missions.md)
