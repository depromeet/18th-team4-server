# AI 작업 절차

AI(Claude Code / Codex)가 이 저장소에서 작업할 때 따르는 절차 문서 모음이다.
"무엇을 만들 것인가"(컨벤션·도메인)는 `docs/conventions/`, `docs/domain/` 에 있고,
여기 문서는 "어떻게 작업을 진행하고 마무리하는가"(작업 순서, 커밋/PR, 리뷰)를 다룬다.

## 파일 목록

| 파일 | 내용 |
|---|---|
| [task-workflow.md](task-workflow.md) | 작업 시 지켜야 할 지시(수정·삭제 범위)와 기본 작업 순서 |
| [git-and-pr.md](git-and-pr.md) | 브랜치 네이밍, 커밋 메시지 규칙, PR·이슈 생성 스킬로의 위임 |
| [codex-review-prompt.md](codex-review-prompt.md) | 로컬 Codex CLI 로 코드 리뷰할 때의 리뷰 기준 (컨벤션 상세는 docs/conventions/ 원본을 읽어 적용) |

## 관련 스킬 위치

절차의 세부 실행은 스킬에 정의돼 있다. 원본은 아래 위치를 따른다.

| 스킬 | 위치 | 역할 |
|---|---|---|
| PR 생성 | `.claude/skills/pr/SKILL.md` | PR 본문 형식·생성 절차 (어휘 규칙 원본은 `docs/conventions/vocabulary.md`) |
| 이슈 생성 | `.claude/skills/issue/SKILL.md` | GitHub 이슈 작성 절차 |
| 구현 후 검증 | `.claude/skills/verify/SKILL.md`, `.claude/agents/verify.md` | 구현 직후 잔존물 검증 체크리스트 |
| Codex 리뷰 | `.codex/skills/readum-review/SKILL.md` | Codex 리뷰 기준(상세는 codex-review-prompt.md 참조) |

## GitHub Actions 의 자동 리뷰

저장소에 Claude 봇 워크플로우 2개가 걸려 있다 (배포용 워크플로우는 없다).

| 워크플로우 | 트리거 | 동작 |
|---|---|---|
| `.github/workflows/claude-code-review.yml` | PR open/synchronize/ready_for_review/reopen | PR 자동 리뷰 |
| `.github/workflows/claude.yml` | 이슈·PR 코멘트/리뷰에 `@claude` 멘션 | 멘션에 응답해 작업 수행 |
