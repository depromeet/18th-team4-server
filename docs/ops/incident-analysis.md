# 운영 장애 분석 (수동 트리거)

## 현재 구성

운영 중 ERROR 가 발생하면 사람이 판단해 분석을 요청하는 흐름이다. 자동 실행은 없다.

```
ERROR 발생
  → Slack 알림 (fingerprint + "분석 이슈 열기" 프리필 링크 — SlackWebhookAppender)
  → 사람이 링크 클릭 → 이슈 생성 (incident 라벨, 본문에 배포 커밋·stacktrace 프리필)
  → incident-analysis.yml 발동 → 배포 커밋 checkout → Claude Code 분석 → 이슈 코멘트
  → Slack 완료/실패 알림
```

- **수집·링크 생성**: `com.readum.infrastructure.logging` 의 `SlackWebhookAppender` 가
  `IncidentFingerprint`(오류 요약 키)와 `IncidentIssueLinkFactory`(프리필 URL)를 사용한다.
  배포 커밋은 빌드 시 gradle-git-properties 가 jar 에 넣은 `git.properties` 에서 읽는다.
- **분석 실행**: `.github/workflows/incident-analysis.yml`. `incident` 라벨이 붙은 이슈가
  트리거다. 이슈 본문의 `<!-- deploy-sha: ... -->` 마커를 파싱해 그 커밋을 checkout 하고,
  Claude Code(`claude-code-action`)가 원인 후보·재현 조건·수정 방향을 이슈 코멘트로 남긴다.
  분석은 읽기 전용 — 코드 수정·PR 생성은 하지 않는다.
- **두 번째 수동 경로**: 링크 없이도 아무 이슈에 `incident` 라벨을 붙이면 분석이 발동한다.
  본문에 배포 커밋 마커가 없으면 기본 브랜치 최신 커밋 기준으로 분석하고 그 사실을 코멘트에 명시한다.
- **필요한 GitHub Actions secrets**:

| secret | 용도 | 비고 |
|---|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | Claude Code 실행 | claude.yml 과 공유, 이미 등록됨 |
| `SLACK_WEBHOOK_URL` | 완료/실패 알림 | 미등록이면 알림만 조용히 생략되고 분석은 정상 동작 |

## 변경 절차와 주의점

- **deploy.yml 과 섞지 않는다.** 이 워크플로우는 배포와 완전히 독립이며, 배포 파이프라인을 건드리지 않는다.
- **Logback Appender 를 무겁게 만들지 않는다.** 앱 쪽 책임은 "Slack 메시지에 링크 한 줄"까지다.
  분석·이슈 조작·상태 관리를 앱에 넣지 않는다. 자동 트리거가 필요해지면 앱이 아니라 이 워크플로우에
  `repository_dispatch` 트리거를 추가하는 방향으로 확장한다 (Epic #100 코멘트의 확장 계획 참조).
- **분석 프롬프트 수정**은 `incident-analysis.yml` 의 `prompt` 를 고친다. 앱 재배포가 필요 없다.
- **비용 제어는 사람이 한다.** 이슈를 만들어야만 분석이 돌므로, 분석이 과하면 그냥 안 누르면 된다.
  같은 이슈에 라벨을 다시 붙이면 재분석이 돈다 (concurrency 로 동시 실행만 막는다).
- **민감정보**: 이슈 본문에 실리는 메시지·stacktrace 는 앱에서 마스킹(`SensitiveDataMasker`)을
  거친 값이다. 마스킹 규칙을 바꾸면 Slack 본문과 이슈 링크 본문에 함께 적용된다.

## 관련 기록

- Epic #100 — 방향 전환 배경(별도 프로세스 설계 폐기 → 수동 트리거 MVP): 이슈 코멘트 참조
- 서브 이슈: #139 (앱 쪽 링크), #140 (이 워크플로우)
