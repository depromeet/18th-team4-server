---
name: pr
description: readum 프로젝트 컨벤션(한국어 4섹션 + 기능/API 스펙/시나리오 리스트, base=dev, closes #N)에 맞춰 GitHub PR 본문을 작성하고 push + gh pr create 까지 진행한다. 본문 어휘는 한국어와 보편적 코드 용어만 사용하며 추상 영어 jargon(fail-fast / silent fallback / ROI / SoT 등)은 한국어로 풀어 쓴다. 사용자가 "PR 올려줘", "PR 생성", "/pr" 등으로 호출할 때 사용.
---

# PR 생성 스킬 (readum)

이 스킬은 readum 의 PR 컨벤션에 맞춰 PR 을 일관된 형식으로 생성한다. 사용자가 PR 생성을 요청하면 다음 절차를 따른다.

## 핵심 원칙

- **PR 생성은 risky action** 이다. 본문/제목을 작성한 후 반드시 사용자에게 보여주고 confirm 받은 다음에야 `gh pr create` 를 호출한다. 자동 생성하지 말 것.
- **본문 형식은 readum 컨벤션 고정**. 다른 프로젝트의 GitHub Generated 푸터 (`🤖 Generated with...`), Co-Authored-By, Test plan 영문 체크리스트 등은 사용하지 않는다.
- **base branch 는 항상 `dev`** (CLAUDE.md 명시).

## 절차

### 1. 현재 상태 점검 (병렬)

다음을 병렬로 실행해 PR 에 포함될 내용을 파악한다.

- `git status` — clean 한지, 미커밋 변경 있는지
- `git branch --show-current` — 현재 브랜치 (이슈 번호 추출 source)
- `git log --oneline origin/dev..HEAD` — 이번 PR 에 포함될 commit 들 (commit message 분석으로 type/scope 결정)
- `git diff --stat origin/dev..HEAD` — 변경 파일 요약

미커밋 변경이 있으면 사용자에게 먼저 commit 할지 묻는다 (이 스킬에서는 commit 자동 생성 X — 별도 작업).

### 2. 최근 PR 형식 참고

```bash
gh pr list --state merged --limit 3 --json number,title,body --jq '.[] | "=== PR #\(.number): \(.title) ===\n\(.body)\n"'
```

이를 통해 현재 팀이 사용하는 PR 본문 형식의 미세한 변화를 감지한다 (예: 섹션 이름 변경, 새 섹션 추가). 본 스킬의 기본 구조와 다르면 최신 형식을 우선한다.

### 3. PR 본문 작성

다음 구조를 따른다 (한국어 본문 + 코드/기술 용어는 영문 유지).

```markdown
## 작업 사항

[배경/이유/구현 결정을 자유로운 단락으로 3~5 단락 서술]
[단순 코드 변경이 아니라 *왜* 와 *어떻게* 가 드러나야 한다]
[중요한 trade-off, scope 결정 이유, 명시적으로 제외한 작업이 있다면 언급]

### 기능

[사용자/리뷰어가 한눈에 보는 기능 단위 bullet list. 카테고리별 bold 헤더로 그룹]

**카테고리 1**
- 기능 설명 (사용자 관점)
- ...

**카테고리 2**
- ...

### API 스펙 (REST API 추가 시)

| 항목 | 값 |
|---|---|
| Method | `GET` / `POST` ... |
| Path | `/api/v1/...` |
| 파라미터 1 | 타입, 필수/선택, 제약 |
| ... | ... |

### 시나리오별 응답 (REST API 추가 시)

[정상 / 검증 실패 / 비즈니스 규칙 위반 / 외부 장애 등 주요 케이스를 코드 블록으로]

**정상** — `요청 예시`
\`\`\`json
HTTP 200
{ ... }
\`\`\`

**검증 실패** — `요청 예시`
\`\`\`json
HTTP 400
{ "error": { "message": "..." } }
\`\`\`

[기타 시나리오 ...]

## 테스트 결과
- [x] `./gradlew test` 통과 (N tests, 0 failed)
- [x] [수동 검증 항목]
- [ ] [리뷰어가 검증할 항목]

## 관련 이슈
- closes #N

## 참고 사항

[리뷰어가 알아야 할 운영 환경 영향, 후속 작업, 환경 셋업 가이드 등]
```

#### 섹션별 작성 가이드

- **작업 사항**: 단순 변경 나열 X. 배경/이유/결정의 narrative. 다른 PR (예: #32, #25) 의 작업 사항 단락을 참고하면 톤 맞춰짐.
- **기능**: "코드 변경" 이 아닌 "사용자가 무엇을 할 수 있게 됐는가" 관점. 예: "키워드로 도서를 검색하고 표지 이미지·제목을 받을 수 있다" (O), "BookSearchService 를 추가했다" (X).
- **API 스펙**: REST API 가 새로 추가/변경됐을 때만. 내부 리팩토링 PR 에서는 생략.
- **시나리오별 응답**: 실제 응답 JSON 을 그대로 보여 줌. 예시 데이터는 commit 검증 시 직접 호출해 받은 진짜 데이터를 우선 (정확성).
- **테스트 결과**: 빌드/단위/통합/수동 검증을 체크박스로. 미완료 항목은 `[ ]` 로 두어 리뷰어 액션 명시.
- **관련 이슈**: 브랜치 이름에서 이슈 번호 추출 (`feature/#22-...` → `closes #22`). 추출 안 되면 사용자에게 묻는다.
- **참고 사항**: 환경변수, 마이그레이션, 후속 작업, 운영 영향 등.

### 4. PR title 작성

Angular convention: `<type>(<scope>): <subject>`

- **type**: 메인 변경의 성격 — `feat` / `fix` / `refactor` / `chore` / `docs` / `style` / `test`
  - 브랜치 이름의 prefix (`chore/`, `feature/` 등) 는 무시. 실제 변경 성격으로 결정.
  - PR 에 여러 type 이 섞이면 가장 큰 변경을 type 으로, 부수 변경은 본문에서 언급.
- **scope**: 변경 범위 — 보통 도메인 이름 (`book`, `auth`, `swagger` 등) 또는 `config`
- **subject**: 한국어 짧은 설명. 마침표 X, 명사형으로 끝내거나 동사형 (`연동`, `추가`, `정비`, `교정`)

예시:
- `feat(book): 알라딘 도서 검색 API 연동`
- `fix(config): application-dev.yml 로거 패키지를 com.readum 으로 교정`
- `refactor(auth): JWT/Refresh Token 레이어 단순화`

### 5. 메타데이터 결정 (Reviewers / Assignees / Labels)

PR 생성 시 다음 메타데이터도 함께 지정한다.

**Labels** — 자동 추출
- 연결된 이슈(`closes #N`)의 라벨을 그대로 PR 라벨로 사용한다.
- 추출 명령:
  ```bash
  gh issue view <N> --json labels --jq '[.labels[].name] | join(",")'
  ```
- 이슈가 없거나 라벨이 비어있으면 사용자에게 라벨을 물어보거나 라벨 없이 진행.

**Assignees** — 기본값
- PR 작성자 자기 자신 (`@me`).
- 추가 담당자가 필요하면 사용자에게 물어 GitHub 핸들 입력받는다.

**Reviewers** — 팀원 풀에서 자기 자신만 제외 (동적 결정)
- 팀원 풀: `psychology50`, `uykm`, `Hheojiwon` (최근 머지된 PR 들의 활발한 리뷰어 기준, 봇 제외)
- 자기 자신은 GitHub 가 reviewer 로 받지 않으므로 풀에서 자동 제외:
  ```bash
  ME=$(gh api user --jq .login)
  REVIEWERS=$(echo "psychology50,uykm,Hheojiwon" | tr ',' '\n' | grep -v "^${ME}$" | paste -sd ',' -)
  ```
- 결과를 `--reviewer "$REVIEWERS"` 로 전달. 다른 팀원이 이 스킬을 사용해도 자기 자신만 제외되어 동작한다.
- 풀이 변경되면(팀 합류/이탈) 본 SKILL.md 의 풀 목록을 갱신. 후보 점검 명령:
  ```bash
  gh pr list --state merged --limit 10 --json reviews --jq '.[].reviews[].author.login' | sort | uniq -c | sort -rn
  # coderabbitai, claude 같은 자동 봇은 풀에서 제외
  ```

### 6. 사용자 confirm

작성한 title, body, 메타데이터(reviewers / assignees / labels)를 한 번에 정리해 사용자에게 보여주고 다음 중 하나의 응답을 받는다:

- **OK / 진행** → 다음 단계
- **수정 요청** → 반영 후 다시 보여주고 confirm
- **취소** → 중단

이 단계를 건너뛰지 말 것. PR 본문은 PR 머지 후에도 release notes 등에 인용되므로 사용자 의도를 정확히 반영해야 한다.

### 7. push + PR 생성

```bash
# 1. branch tracking 없으면 push -u
git push -u origin <current-branch>

# 2. PR 생성 (HEREDOC 으로 본문 전달, base=dev 고정, 메타데이터 옵션 포함)
gh pr create \
  --base dev \
  --title "..." \
  --reviewer "<핸들1>,<핸들2>" \
  --assignee "@me" \
  --label "<라벨1>,<라벨2>" \
  --body "$(cat <<'EOF'
[본문 전체]
EOF
)"
```

옵션은 비어있을 수 있으면 생략한다 (예: 라벨 추출 결과가 비면 `--label` 자체를 빼서 호출).

성공 시 반환되는 PR URL 을 사용자에게 알린다.

## 어휘 가이드

본문은 **한국어 + 보편적으로 통용되는 코드/기술 용어** 만 사용한다. 추상 개념을 영어로 압축한 표현(소위 AI 가 만든 듯한 jargon)은 사용하지 않고 평이한 한국어로 풀어 쓴다.

**피할 표현 → 한국어 대체 예시**

| 피할 표현 | 한국어 대체 |
|---|---|
| fail-fast | 즉시 실패 응답 / 호출을 빠르게 끊는다 |
| silent fallback | 빈 결과 대신 다른 응답으로 조용히 바뀜 |
| fire-and-forget | 결과를 기다리지 않고 비동기 실행 / 응답 대기 없이 백그라운드에서 실행 |
| swallow (예외를 swallow) | 예외를 잡아 로그만 남기고 외부로 안 던짐 |
| happy path | 정상 흐름 |
| best-effort | 가능한 범위에서 시도, 실패해도 통과 |
| short-circuit | 조건 만족 시 이후 단계 건너뜀 |
| noop | 아무 일도 안 함 |
| ROI | 비용 대비 효용 / 그만큼의 가치가 없음 |
| SoT (Source of Truth) | 데이터 출처 기준 / 정답을 갖는 곳 |
| stateless | 상태 저장 없이 / 상태를 두지 않고 |
| graceful degradation | 부분 장애 시 점진적 성능 저하 |
| race condition | 동시성 충돌 |
| eventually consistent | 일정 시간 후 데이터가 맞춰짐 |

**그대로 영문을 쓰는 경우** — 코드 식별자, 표준 스펙, 잘 알려진 라이브러리/타입 이름:
- 클래스/메서드/패키지: `RestClient`, `JdkClientHttpRequestFactory`, `Adapter`, `Port`, `BookSearchService`
- HTTP/REST 표준: `GET`, `POST`, `400`, `Bearer Token`
- 잘 정착된 약어: `JWT`, `JPA`, `MVP`

**판단 기준**: 그 용어가 코드 식별자나 표준 스펙에 그대로 등장하면 영문 유지, 추상 개념을 영어로 줄여 쓴 것이면 한국어로 풀어 쓴다.

## readum 고유 컨벤션 디테일

- **언어**: 한국어 본문 + 영문 기술 용어 그대로 유지 (예: `RestClient`, `JdkClientHttpRequestFactory`, `Adapter`, `Port`).
- **base branch**: `dev` 고정. main 직행 PR 금지.
- **이슈 연결**: `closes #N` 으로 자동 close.
- **PR 본문 푸터**: `🤖 Generated with [Claude Code]` 같은 자동 생성 푸터 / `Co-Authored-By` 라인 / Test plan 영문 체크리스트 등은 **추가하지 않음**. 본문은 사람이 쓴 톤 유지.
- **CodeRabbit 자동 코멘트**: 머지 시 자동 추가됨. 우리 PR 본문에 미리 포함하지 않음.
- **commit footer 와 PR 본문은 별개**: commit 메시지에는 `Co-Authored-By: Claude Opus 4.7` 추가하지만, PR 본문에는 포함하지 않는다.

## 안티패턴 (피할 것)

- 단순 코드 diff 나열 ("X.java 파일 추가, Y.java 수정") — 기능/이유 관점으로 재구성.
- 추상 영어 jargon (fail-fast, silent fallback, fire-and-forget, swallow, happy path, best-effort, noop, ROI, SoT, stateless 등) — 위 어휘 가이드대로 한국어로 풀어 쓰기.
- 영문 본문, 영문 섹션 헤더 (`## Summary`, `## Test plan`) — 한국어로.
- 사용자 confirm 없이 `gh pr create` 자동 호출 — 반드시 본문 보여주고 OK 받기.
- 미커밋 변경이 남은 채로 PR 생성 — commit 부터.
- `--base main` 또는 base 미명시 — `--base dev` 명시.
- reviewer/assignee/label 누락 — 라벨은 연결 이슈에서 자동 추출, reviewer 는 사용자에게 묻기.
