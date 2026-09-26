# 작업 워크플로우

큰 기능을 한 번에 구현하지 않고, 요구사항을 문서와 이슈로 고정한 뒤 테스트 기반으로
구현하기 위한 순서다. 먼저 **작업 유형을 분류**하고, 유형별 흐름을 따르며, 단계마다
**이 저장소의 어떤 문서를 읽을지** 고른다.

- 범용 절차(브레인스토밍·TDD·리뷰 등)는 superpowers 스킬에 위임한다. 아래 흐름 표에 어느 스킬인지 표기한다.
- 작업 중 산출물(PRD·스펙 등)은 커밋하지 않는다. gitignored `docs/superpowers/specs/{작업}/` 에 둔다.
  팀과 공유되는 영속 기록은 GitHub 이슈·PR 이다.
- 모든 작업에 PRD 를 강제하지 않는다. 사소한 단건 작업은 이 흐름을 태우지 않고 바로 처리한다.

진입은 `go` 스킬(`/go` 또는 "~ 해보려고 해" 같은 착수 문구)로 시작한다.

## 1. 작업 유형 분류

| 유형 | 무엇 | 이슈 매핑 |
|---|---|---|
| Feature | 새 사용자 가치·동작 | `Task` (여러 개로 쪼개지면 `Epic` + 하위 `Task`) |
| Bug Fix | 의도와 다른 동작 수정 | `bug` |
| Refactoring | 외부 동작 유지, 내부 개선 | `Task` + `Chore` |
| Chore | 설정·빌드·의존성·개발환경 | `Task` + `Chore` |
| Documentation | 문서만 수정 | `Task` |

> 이슈 분류 체계·라벨·템플릿의 원본은 `.claude/skills/issue/SKILL.md` 와 `.github/ISSUE_TEMPLATE/` 다.
> 여기서 새 라벨이나 새 이슈 템플릿을 만들지 않는다.

## 2. 유형별 흐름

각 단계는 **목적 / 위임(도구) / 산출물 / 다음 단계 조건** 을 가진다. 산출물 경로의
`{작업}` 은 gitignored `docs/superpowers/specs/{작업}/` 를 가리킨다.

### Feature

`요구사항 캐묻기 → PRD → 브레인스토밍 → 스펙 → 이슈 → Skeleton → TDD → 구현 → 리뷰`

| 단계 | 위임/도구 | 산출물 |
|---|---|---|
| 요구사항 캐묻기 | `/grill-me` | (대화) |
| PRD | §3 참고해 직접 | `{작업}/prd.md` |
| 브레인스토밍 | `superpowers:brainstorming` | `{작업}/brainstorming.md` |
| 스펙 | `superpowers:writing-plans` | `{작업}/spec.md` |
| 이슈 | `issue` 스킬 (§5) | GitHub 이슈 |
| Skeleton | `superpowers:writing-plans` 의 뼈대 먼저 | 골격 코드 |
| TDD | `superpowers:test-driven-development` | 실패하는 테스트 |
| 구현 | `superpowers:executing-plans` | 구현 코드 |
| 리뷰 | `superpowers:requesting-code-review` + `/verify` + `/pr` | PR |

- 다음 단계 조건: 스펙이 확정되기 전에는 이슈를 만들지 않는다. 테스트 없이 구현을 시작하지 않는다.

### Bug Fix

`버그 리포트 → 재현 → 원인 분석 → 수정 스펙 → 이슈 → 실패하는 재현 테스트 → 수정 → 회귀 테스트 → 리뷰`

- 원인 분석: `superpowers:systematic-debugging`
- 산출물: `{작업}/bug-report.md`, `{작업}/fix-spec.md`
- **구현보다 먼저 실패하는 재현 테스트를 만든다.** 원인 분석 없이 바로 고치지 않는다. 수정 중 리팩토링을 섞지 않는다.

### Refactoring

`리팩토링 제안 → 현재 동작을 회귀 테스트로 고정 → 리팩토링 스펙 → 이슈 → 리팩토링 → 회귀 테스트 → 리뷰`

- 산출물: `{작업}/refactor-proposal.md`, `{작업}/refactor-spec.md`
- **외부 동작을 바꾸지 않는다.** 기능 변경이 필요하면 별도 이슈로 분리한다. 리팩토링 전에 현재 동작을 회귀 테스트로 고정한다.

### Chore / Documentation

`작업 스펙(또는 문서 계획) → 이슈 → 작업 → 검증 → 리뷰`

- 산출물: `{작업}/chore-spec.md` 또는 `{작업}/doc-plan.md`

## 3. 단계별 읽을 이 저장소 문서

항상 필요한 것만 읽는다. 무작정 전체를 읽지 않는다.

| 단계 | 읽을 문서 |
|---|---|
| 분류·시작 | 이 문서, `docs/README.md` |
| PRD | 필요 시 `docs/domain/README.md`, `docs/domain/scenarios/*` |
| 스펙(공통) | `docs/conventions/testing.md`, `docs/conventions/package-structure.md`, `docs/architecture/system-overview.md` |
| 스펙(성격별) | DB·트랜잭션 → `conventions/transaction.md` / API → `conventions/api-and-swagger.md`, `dto.md` / 예외 → `conventions/exception-handling.md` / Entity → `conventions/entity.md` / 외부 연동 → `architecture/external-integrations.md` / 인증 → `architecture/security-architecture.md` |
| 구현 | `docs/conventions/README.md` 에서 해당 규칙. **각 계층의 `example/` 참조 구현체(엔티티·서비스·DTO·컨트롤러 한 벌)를 보고 무엇을 어디에 둘지 확인한다.** |
| 리뷰 | §4, `docs/ai-workflow/git-and-pr.md` |

## 4. 단일 관심사 원칙

하나의 이슈·PR 은 하나의 관심사만 다룬다. 설명을 한 문장으로 썼을 때
"그리고 / 하는 김에" 가 들어가면 관심사가 섞인 것이다. 다음 중 둘 이상이 겹치면 나누는 것을 기본으로 한다 —
기능 추가 · 버그 수정 · 리팩토링 · 테스트 정리 · 문서 정리 · 운영 설정 변경 · 성능 튜닝 ·
아키텍처 변경 · 의존성 변경 · API 계약 변경 · DB 스키마 변경.

작업 완료에 필요한 최소 테스트·문서·설정은 같은 이슈·PR 에 둘 수 있다. 작업 중 새로 발견한 것은
바로 구현하지 말고 **후속 작업**으로 분리한다.

## 5. 이슈 생성 시점

스펙(또는 수정 스펙·리팩토링 스펙)이 확정된 뒤 이슈를 만든다. 작은 버그는 리포트와 이슈를 함께 써도 된다.
큰 스펙은 여러 이슈로 쪼갠다(예: 상태 모델 → 적재 → 선점 → 회수 → 재시도 → 운영 로그). 생성 절차는 `issue` 스킬을 따른다.
