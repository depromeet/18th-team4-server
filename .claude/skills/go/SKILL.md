---
name: go
description: readum 새 작업 착수 진입점. 작업을 유형(Feature/Bug Fix/Refactoring/Chore/Documentation)으로 분류하고, 규모에 맞게 다음 한 걸음과 그 단계에서 읽을 저장소 문서를 안내한다. 사용자가 "~ 해보려고 해", "~ 작업해보려고 해", "~ 처리해보려고 해", "이거 어떻게 진행하지", "/go" 처럼 새 구현 작업 착수를 알릴 때 사용.
---

# 작업 진입점 (readum)

새 작업을 시작할 때 유형을 분류하고 **다음 한 걸음만** 안내한다. 전체 흐름·유형별 위임처·
단계별 읽을 문서의 원본은 [`docs/ai-workflow/development-workflow.md`](../../../docs/ai-workflow/development-workflow.md) 다.
여기서 그 내용을 복붙하지 말고, 그 문서를 읽어 적용한다.

## 절차

0. **규모 판단.** 요청이 사소하거나(오타·로그 확인·한 줄 수정) 사용자가 이미 명확한 단건 지시를 했으면,
   워크플로우를 태우지 않고 "가벼운 작업이라 바로 진행합니다" 하고 넘어간다. 아래는 스펙까지 갈 만한 작업일 때만.
1. `docs/ai-workflow/development-workflow.md` 를 읽는다.
2. 사용자 요청을 유형으로 분류한다(§1). 모호하면 사용자에게 묻는다.
3. 아래를 **작업 계획으로 보고**한다:
   - 작업 유형
   - 다음 단계 하나 + 그때 쓸 스킬(위임처)
   - 그 단계에서 읽을 저장소 문서(§3)
   - 이번 작업의 단일 관심사 / 포함·제외 범위 / 후속 작업 후보
4. 다음 단계로 넘어간다(위임):
   - 요구사항 캐묻기 → `/grill-me`
   - 브레인스토밍 → `superpowers:brainstorming` · 스펙 → `superpowers:writing-plans`
   - TDD → `superpowers:test-driven-development` · 구현 → `superpowers:executing-plans`
   - 버그 원인 분석 → `superpowers:systematic-debugging`
   - 이슈 → `issue` 스킬 · PR → `pr` 스킬 · 구현 후 검증 → `verify` 스킬

## 하지 않을 것

- 새 이슈 라벨·이슈 템플릿·훅을 만들지 않는다.
- 작업 산출물(PRD·스펙 등)은 `docs/superpowers/specs/{작업}/` 에 둔다(커밋 금지).
- 한 번에 여러 단계를 건너뛰지 않는다. 다음 한 걸음만 안내한다.
