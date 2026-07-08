# slack-receiver

Slack 장애 알림의 "분석 이슈 만들기" 버튼 클릭을 받아 GitHub `repository_dispatch(incident-analysis)`
를 발동하는 초경량 수신기. readum 앱과 별개 컨테이너로 EC2 에 상주한다.

## 흐름
Slack 버튼 → nginx `/slack/incident-actions` → 이 서비스(:8090) → GitHub repository_dispatch
→ `.github/workflows/incident-analysis.yml`

## 필요한 환경변수 (서버 /opt/readum/.env = ENV_FILE secret)
- `SLACK_SIGNING_SECRET` — Slack 앱 Basic Information 의 Signing Secret
- `INCIDENT_DISPATCH_TOKEN` — GitHub 토큰. fine-grained PAT 로 이 저장소 **Contents: Read and write**
  (repository_dispatch 호출 권한). 최소 권한만.
- `GITHUB_REPOSITORY` — `depromeet/18th-team4-server` (compose 에 고정)
- `PORT` — 8090 (compose 에 고정)

## Slack 앱 1회 설정 (수동)
1. 기존 Incoming Webhook 이 속한 Slack 앱에서 **Interactivity & Shortcuts** 를 켠다.
2. Request URL 에 `https://api.readum.kr/slack/incident-actions` 등록.
3. **Basic Information → Signing Secret** 값을 `SLACK_SIGNING_SECRET` 으로 ENV_FILE secret 에 넣는다.
4. GitHub fine-grained PAT 발급(Contents RW) → `INCIDENT_DISPATCH_TOKEN` 으로 ENV_FILE secret 에 넣는다.

## 배포
`infra/slack-receiver/**` 변경 시 `slack-receiver-deploy.yml` 이 자동으로 이미지 빌드→서버 docker load→재기동.
수동 재기동: 서버에서 `sudo docker compose --env-file /opt/readum/.env -f <compose> up -d slack-receiver`.

## 로컬 테스트
`cd infra/slack-receiver && go test ./...`
