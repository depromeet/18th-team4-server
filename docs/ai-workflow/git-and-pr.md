# Git · PR 절차

## 브랜치 네이밍

```text
main ← dev ← feature|bugfix|hotfix/{이슈번호}-{간단한-설명}
```

- 기준 브랜치는 항상 `dev` 다.

## 커밋 메시지 (Angular Convention)

```text
<type>(<scope>): <subject>
```

| type | 설명 |
|------|------|
| feat | 새로운 기능 추가 |
| fix | 버그 수정 |
| docs | 문서 변경 |
| style | 코드 포맷팅 (기능 변경 없음) |
| refactor | 리팩토링 (기능 변경 없음) |
| test | 테스트 추가/수정 |
| chore | 빌드, 설정 등 기타 변경 |

## PR · 이슈 생성

PR 본문 형식·생성 절차·어휘 가이드의 원본은 스킬에 있다. 여기에 복사하지 않고 링크로 위임한다.

- **PR 생성**: [.claude/skills/pr/SKILL.md](../../.claude/skills/pr/SKILL.md)
- **이슈 생성**: [.claude/skills/issue/SKILL.md](../../.claude/skills/issue/SKILL.md)
