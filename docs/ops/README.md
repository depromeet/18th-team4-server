# ops — 인프라·운영

배포 인프라 구성 등 운영 관련 문서를 모은다.

| 파일 | 내용 |
|------|------|
| [infrastructure.md](infrastructure.md) | 개발 서버 AWS 인프라 구성 (EC2·RDS·도메인·접속 방법) |
| [deployment.md](deployment.md) | blue/green 배포 구조·절차·최초 전환(cutover) runbook (#108) |
| [_template.md](_template.md) | 운영 문서 작성 템플릿 |

## 이 디렉토리에 적지 않는 것 (2026-07-05 결정)

- **코드로 확인할 수 없는 콘솔 설정 세부** (보안 그룹 규칙, 버킷 목록, IAM 정책 등) — 금방 낡아 거짓이 되므로 AWS 콘솔을 원본으로 본다.
- **로컬 개발 환경 세팅** — 환경 변수의 원본은 `src/main/resources/application*.yml` 의 `${...}` 플레이스홀더, 빌드/실행 명령은 CLAUDE.md 에 있다.

## 아직 없는 문서와 작성 시점

- CI/CD 파이프라인 문서 — GitHub Actions 빌드·배포 워크플로우를 구성하는 작업에서 [deployment.md](deployment.md) 에 절을 추가한다.
- 운영 로그/모니터링 확인 문서 — 로그 기반 에러 이슈 자동화 작업(Epic #100 계열)에서 함께 작성한다.
