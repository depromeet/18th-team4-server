---
name: issue
description: readum 프로젝트 컨벤션(한국어 본문 + bug/task/user-story 템플릿 + Epic 메인 이슈, 라벨·마일스톤은 조회해 사용자에게 물어 부착, Project '18th team-4 server backlog' 연결, Epic↔서브 이슈 연결)에 맞춰 GitHub 이슈를 작성하고 사용자 confirm 후 gh issue create 까지 진행한다. 사용자가 "이슈 등록", "이슈 만들어줘", "이슈 파줘", "메인/Epic 이슈 열고 서브 이슈로", "/issue" 등으로 호출할 때 사용.
---

# 이슈 등록 스킬 (readum)

이 스킬은 readum 의 이슈 컨벤션에 맞춰 GitHub 이슈를 일관된 형식으로 등록한다. 자매 스킬: `pr` (동일한 어휘·톤 규칙 공유). 어휘 규칙은 CLAUDE.md "어휘 / 용어 작성 규칙" + `.claude/skills/pr/SKILL.md` "어휘 가이드" 를 그대로 따른다 — 그 단어만 봐선 모호한 용어(추상 영어 jargon, `Tier`/`production` 같은 약칭)는 쉬운 우리말로 풀어 쓴다. `Trade-off` 등 보편 전문어·코드 식별자는 유지.

저장소 좌표: owner `depromeet`, repo `18th-team4-server`. GitHub Project: **`18th team-4 server backlog`** (org `depromeet`).

## 핵심 원칙

- **이슈 생성은 외부로 나가는 행위**. 제목/본문/메타데이터(라벨·마일스톤·프로젝트·Epic 연결)를 모두 정리해 사용자에게 보여주고 confirm 받은 다음에야 `gh issue create` 를 호출한다. 자동 생성 금지.
- **라벨과 마일스톤은 사용자에게 물어 정한다.** 스킬이 임의로 확정하지 않는다. 항상 먼저 조회해서 실제 목록을 사용자에게 보여주고 `AskUserQuestion` 으로 고르게 한다 (스킬은 추천만 제시).
- **모든 이슈는 Project `18th team-4 server backlog` 에 연결한다** (기존 이슈와 동일). 토큰에 `project` 권한이 없으면 사용자에게 갱신을 요청한다(아래 5-3).
- **본문 템플릿 고정**: `bug` / `task` / `user-story` 는 `.github/ISSUE_TEMPLATE/{bug,task,user-story}.md` 구조 그대로. `Epic` 은 템플릿 파일이 없으므로 아래 정의된 경량 구조를 쓴다. `gh --template` 옵션에 의존하지 말고 본문을 직접 구성한다.

## 절차

### 1. 이슈 유형 결정

작업 성격으로 고른다. 모호하면 `AskUserQuestion` 으로 사용자에게 묻는다.

| 유형 | 템플릿 | 언제 | 추천 기본 라벨 |
|---|---|---|---|
| **Epic** (메인) | 파일 없음 (경량 구조) | 여러 이슈를 묶는 큰 목표·이니셔티브 | `Epic` (+ 도메인) |
| **bug** | `bug.md` | 결함 보고 (재현·기대·실제 동작) | `bug` |
| **task** | `task.md` | 개발자 관점 구현/리팩토링/비기능 작업 | `Task` (+ `Chore`) |
| **user-story** | `user-story.md` | 사용자 관점 기능 요청 ("~로서 ~할 수 있다") | `User Story` |

> 리팩토링·테스트 정비·아키텍처 개선은 **task** + `Chore` (사용자 노출 기능 아님 → user-story 아님). 여러 개선 작업을 묶는 상위 목표는 **Epic** 으로 열고 각각을 서브 이슈(task/user-story)로 단다.

### 2. 현황·형식 참고 (병렬)

```bash
# 최근 이슈 형식 (제목 톤·라벨 조합 감지)
gh issue list --state all --limit 5 --json number,title,labels \
  --jq '.[] | "#\(.number) [\([.labels[].name]|join(","))] \(.title)"'

# 기존 Epic 목록 (서브 이슈로 달 상위 Epic 후보 / 제목 톤)
gh issue list --label Epic --state all --limit 20 --json number,title \
  --jq '.[] | "#\(.number) \(.title)"'
```

최근 이슈와 톤이 다르면 최신 형식을 우선한다. 코드 근거가 필요하면 grep/Read 로 파일·라인 확인.

### 3. 본문 작성 (유형별)

한국어 본문 + 코드/기술 용어는 영문 유지. 빈 placeholder 줄은 해당 없으면 삭제.

**Epic** (메인 이슈 — 템플릿 파일 없음, 경량 구조)
```markdown
## 배경 / 목표
(이 Epic 이 묶는 작업의 목적·이유. 왜 지금 하는가)

## 범위
- 포함: ...
- 제외(또는 후속): ...
```
> 기존 Epic(#1~#8)은 본문이 짧다. 배경/목표는 필수, 범위는 있으면 적고 없으면 줄 삭제. **하위 이슈 목록을 본문에 손으로 적지 않는다** — GitHub 네이티브 서브 이슈 패널이 목록·진행률을 자동으로 보여주므로 수동 체크리스트는 중복이고 상태가 어긋난다 (5-4).

**bug**
```markdown
## 재현 단계
1. ...
2. ...

## 기대 동작
...

## 실제 동작
...

## 환경
- API 버전:
- 로그/스크린샷:
```

**task**
```markdown
## 작업 내용
(구현/리팩토링할 내용을 구체적으로. *왜* 와 *무엇* 이 드러나야 한다)

## 기술적 고려사항
(아키텍처 결정, 영향 범위, trade-off, 주의점)

## 완료 조건
- [ ] 구현 완료
- [ ] 테스트 코드 작성/통과
- [ ] PR 리뷰 통과

## 관련
- 관련 Epic: #00   (서브 이슈면 상위 Epic 번호. 없으면 줄 삭제)
- Story: #00       (연관 없으면 줄 삭제)
```

**user-story**
```markdown
## 스토리
~로서, ~를 할 수 있다. 그래서 ~할 수 있다.

## 상세 설명
(기획 의도, 관련 화면, 비즈니스 규칙)

## 인수 조건 (Acceptance Criteria)
- [ ] ~하면 ~한다

## Tasks
- [ ] #123 ...

## 참고
- 관련 Epic: #00   (서브 이슈면 상위 Epic 번호. 없으면 줄 삭제)
```

### 4. 제목 작성

- **언어**: 한국어. user-story 문장형은 마침표 허용, Epic/task/bug 명사형은 마침표 X.
- **형식**:
  - Epic: `Epic: <한국어 제목>` (예: `Epic: AI 채팅`, `Epic: QA & 런칭`)
  - user-story: `사용자는 ~할 수 있다` 류 문장
  - task/bug: 명사구 (예: `사용자 세션 정보 조회`)
- 이슈 제목에 `feat(scope):` 같은 commit prefix 금지.

### 5. 메타데이터 (사용자에게 물어 확정)

#### 5-1. 라벨 — 조회 후 질문

```bash
gh label list --json name,description --jq '.[] | "\(.name) — \(.description)"'
```

자주 쓰는 라벨:

| 라벨 | 용도 |
|---|---|
| `Epic` | 여러 이슈를 묶는 메인 이슈 |
| `User Story` / `Task` / `bug` | 이슈 유형 기본 |
| `Sub Task` | Task 하위 분할 |
| `Chore` | 비기능 작업 (리팩토링·빌드·정비) |
| `API` | REST API 추가/변경 동반 |
| `AI/LLM` | LLM·프롬프트·AI 파이프라인 |
| `Infra` / `Ops` | 인프라·배포 / 운영 |
| `QA` | 품질·테스트 중심 |
| `documentation` | 문서 변경 |

→ 유형 기준 추천 조합을 제시하되, 실제 라벨 목록을 보여주고 **`AskUserQuestion`(multiSelect) 으로 사용자가 최종 선택**하게 한다. 스킬이 단독 확정하지 않는다.

#### 5-2. 마일스톤 — 조회 후 질문

```bash
gh api repos/depromeet/18th-team4-server/milestones --jq '.[] | "\(.title)"'
```

→ 열려 있는 마일스톤 목록을 보여주고 **`AskUserQuestion` 으로 사용자가 선택**(또는 "없음"). 임의로 고르지 않는다.

#### 5-3. Project 연결 — `18th team-4 server backlog`

**이 저장소 Project 에는 "신규 이슈 자동 추가" 워크플로우가 걸려 있다.** 저장소에 새 이슈를 만들면 자동으로 이 프로젝트에 들어간다 (검증: `--project` 옵션 없이 만든 #50·#51 이 프로젝트에 연결됨).

- 따라서 `gh issue create` 에 `--project` 를 줄 필요 없고, `project` 토큰 권한이나 `gh auth refresh -s project` 도 **불필요**하다.
- `project` 권한이 없으면 `gh issue view <n> --json projectItems` 는 빈 값으로 보인다. 이는 API 가 못 읽는 것일 뿐 연결이 안 된 게 아니다 — 확인이 필요하면 GitHub 웹 UI 로 본다.
- 예외: 자동 추가 워크플로우가 꺼져 있어 누락된 경우에만 수동 연결한다 (`project` 권한 확보 후 `gh project item-add <번호> --owner depromeet --url <이슈URL>`).

#### 5-4. Epic ↔ 서브 이슈 연결 방식

- 부모↔자식 추적의 **기준은 GitHub 네이티브 서브 이슈** 다 (생성 후 5-5/7 에서 `addSubIssue`). 부모 Epic 본문에 `## 하위 이슈` 수동 체크리스트를 **두지 않는다** — 네이티브 패널이 목록·진행률을 자동으로 보여주므로 중복이고 갱신이 어긋난다.
- 자식 본문에는 기존 저장소 컨벤션대로 `## 관련` / `## 참고` 에 `- 관련 Epic: #<상위번호>` 백링크 한 줄을 적는다 (검색·diff 가독성용. #29 등 기존 이슈와 표기 일치).

#### 5-5. Assignees

기본 `@me`. 추가 담당자 필요하면 GitHub 핸들을 사용자에게 묻는다.

### 6. 사용자 confirm

**제목 / 유형 / 본문 / 라벨(선택안) / 마일스톤(선택안) / Project / Epic 연결 / assignee** 를 한 번에 정리해 보여주고, 후속 브랜치를 만들 경우 브랜치 이름(8단계)도 함께 제시한다. 응답:

- **OK / 진행** → 다음 단계
- **수정 요청** → 반영 후 다시 confirm
- **취소** → 중단

이 단계를 건너뛰지 말 것. 라벨·마일스톤은 5단계에서 이미 사용자가 고른 값을 그대로 다시 요약해 최종 확인.

### 7. 이슈 생성 + 연결

```bash
# 1) 이슈 생성 (라벨·마일스톤은 사용자가 5단계에서 고른 값)
#    --project 는 주지 않는다 — 저장소 Project 자동 추가 워크플로우가 처리 (5-3)
gh issue create \
  --title "..." \
  --label "<사용자선택1>,<사용자선택2>" \
  --assignee "@me" \
  --milestone "<사용자선택 마일스톤>" \
  --body "$(cat <<'EOF'
[본문 전체]
EOF
)"
```

- 사용자가 마일스톤 "없음" 을 골랐으면 `--milestone` 제거. 라벨 미선택이면 `--label` 제거.
- Project 연결은 자동이다 (5-3). 토큰 갱신·`--project`·`item-add` 를 기본 절차로 넣지 않는다.

**서브 이슈 네이티브 연결** (상위 Epic #E, 서브 이슈 #S):
```bash
PID=$(gh api graphql -f query='query($n:Int!){repository(owner:"depromeet",name:"18th-team4-server"){issue(number:$n){id}}}' -F n=E --jq '.data.repository.issue.id')
CID=$(gh api graphql -f query='query($n:Int!){repository(owner:"depromeet",name:"18th-team4-server"){issue(number:$n){id}}}' -F n=S --jq '.data.repository.issue.id')
gh api graphql -f query='mutation($p:ID!,$c:ID!){addSubIssue(input:{issueId:$p,subIssueId:$c}){issue{number}}}' -f p="$PID" -f c="$CID"
```
- `addSubIssue` 스키마가 바뀌어 실패하면 `gh api graphql` 로 현재 스키마를 확인해 보정한다. 실패해도 본문의 `- 관련 Epic: #E` 텍스트 연결은 유지되므로 사용자에게 수동 연결을 안내한다.

성공 시 이슈 URL·번호를 사용자에게 알린다. 서브 이슈면 위 네이티브 연결까지 끝내야 완료다 (부모 Epic 본문은 손대지 않는다 — 네이티브 패널이 자동 반영).

### 8. 후속: 작업 브랜치 생성 (선택)

사용자가 명시적으로 브랜치까지 원할 때만. 이슈만 등록하고 작업은 나중인 경우 생략한다.

- **type**: bug → `bugfix`, task/user-story → `feature`, 운영 긴급 → `hotfix` (CLAUDE.md). Epic 은 보통 직접 브랜치를 만들지 않는다(서브 이슈에서 만든다).
- **이름**: `<type>/#<이슈번호>-<영문-슬러그>` (예: `feature/#52-remove-test-only-entity-factories`). `#N` 은 `pr` 스킬이 이슈 번호를 추출하는 키라 필수.
- **base**: 항상 `dev`.

```bash
git switch dev
git pull --ff-only origin dev
git switch -c <type>/#<N>-<슬러그>
```

미커밋 변경이 있으면 먼저 사용자에게 처리를 묻는다.

## readum 고유 컨벤션 디테일

- **언어**: 한국어 본문 + 영문 기술 용어 유지. 모호한 용어 금지(CLAUDE.md 어휘 규칙).
- **유형 4종**: Epic / bug / task / user-story. bug/task/user-story 만 템플릿 파일 존재, Epic 은 경량 구조.
- **라벨·마일스톤은 항상 사용자 선택**. 조회 → 추천 → `AskUserQuestion` → 확정. 스킬 단독 결정 금지.
- **라벨 표기**: 항상 `gh label list` 실제 이름. 템플릿 frontmatter 소문자(`task`) 신뢰 금지 (실제는 `Task`).
- **Project**: 저장소 자동 추가 워크플로우가 신규 이슈를 `18th team-4 server backlog` 에 자동 연결. 수동 연결·토큰 갱신 불필요 (5-3).
- **Epic↔서브**: 부모↔자식 추적은 네이티브 서브 이슈로만. 부모 Epic 본문에 수동 하위 목록 작성 금지. 자식 본문엔 `- 관련 Epic: #N` 백링크 한 줄(기존 컨벤션).
- **이슈 ↔ PR**: 이슈는 `closes #N` 으로 PR 에서 닫는다(이슈 본문엔 `closes` 안 씀).
- **브랜치**: `dev` 기준, `<type>/#<N>-<슬러그>` (`#N` 필수). main 직행 금지.
- **푸터 금지**: `🤖 Generated with...` / `Co-Authored-By` 등 자동 푸터를 이슈 본문에 넣지 않는다.

## 안티패턴 (피할 것)

- 사용자 confirm 없이 `gh issue create` 자동 호출.
- 라벨/마일스톤을 사용자에게 묻지 않고 스킬이 임의 확정.
- Project 연결을 위해 불필요하게 `--project`·토큰 갱신·`item-add` 를 시도 (자동 추가됨 — 5-3).
- 서브 이슈인데 네이티브 서브 이슈 연결 누락.
- 부모 Epic 본문에 수동 `## 하위 이슈` 체크리스트 작성 (네이티브 패널과 중복·갱신 어긋남).
- 템플릿 무시한 자유 형식 / 영문 섹션 헤더 / 빈 placeholder 줄 방치.
- 라벨을 템플릿 소문자 값(`task`)으로 지정 → 실제 라벨(`Task`) 불일치.
- 모호한 용어·추상 영어 jargon (CLAUDE.md 어휘 규칙 위반).
- 리팩토링/비기능을 user-story 로 등록 (→ task + `Chore`).
- 이슈 제목에 `feat(scope):` commit prefix 부착.
- 브랜치 이름에서 `#N` 누락 → `pr` 스킬이 `closes #N` 추출 실패.
