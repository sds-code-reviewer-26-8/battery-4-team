# 3. 기능 추가 미션 동작 영상

기능 추가를 한 결과 시연 영상을 살펴봅니다.

기능 4개가 모두 main에 머지되었고, 영상은 머지된 main(`9745262`)에서 찍었습니다.

영상은 별도 포트·메모리 DB로 띄운 앱에서 Playwright로 화면을 단계별로 캡처한 뒤, 설명 자막을 붙여 GIF(폭 640px, 10초 안팎)로 만들었습니다.

<br>

## 기능 1. 고객사 이름 입력

주문할 때 고객사 이름(선택, 최대 100자)을 입력하고, 주문 후에도 수정할 수 있습니다. 주문 작성자만 수정할 수 있고, 완료·삭제된 주문은 수정할 수 없습니다.

![고객사 이름 입력 기능](assets/feature1-customer.gif)

- 담당: 허주 프로 · PR: [#29](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/29)

<br>

## 기능 2. 필요 납기일

주문 추가 화면에서 필요 납기일(5~50일) 또는 "가능한 빨리"를 정하면, 소요 일수가 납기를 넘는 안에 "납기 초과 N일"이 표시됩니다. 주문을 확정하면 납기가 주문에 저장되어 목록과 상세에도 보입니다.

![필요 납기일 기능](assets/feature2-due-date.gif)

- 담당: 정혜미 프로 · PR: [#30](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/30)

<br>

## 기능 3. 회원 가입

로그인 화면의 "회원 가입"으로 계정을 만들고, 그 계정으로 로그인해 주문 목록을 봅니다. 가입 회원은 admin과 같은 권한을 가집니다.

![회원 가입 기능](assets/feature3-signup.gif)

- 담당: 장영은 프로 · PR: [#28](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/28)

<br>

## 기능 4. 공장 운영비

마스터 데이터에서 나라별 공장 운영비(억/일)를 정하면, 시뮬레이션이 공장 운영 일수에 곱해 비용에 반영합니다. 값을 바꾸면 총비용과 "총비용 최소" 안이 달라집니다.

![공장 운영비 기능](assets/feature4-operating-cost.gif)

- 담당: 전예리 프로 · PR: [#31](https://github.com/sds-code-reviewer-26-8/battery-4-team/pull/31)

---

[← 이전: Loop 설계](02-loop.md) · [목차](README.md) · [다음: PR & 코드리뷰 →](04-review.md)
