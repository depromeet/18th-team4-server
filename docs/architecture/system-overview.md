# 시스템 개요

## 기술 스택

| 항목 | 값 |
|------|----|
| 프레임워크 | Spring Boot 4.0.5 (`spring-boot-starter-webmvc`, servlet 기반) |
| 언어 | Java 25 |
| 빌드 | Gradle 9.4.1 |
| DB 접근 | Spring Data JPA |
| 보안 | Spring Security |

## 모듈 구성

단일 Gradle 모듈이다 (`settings.gradle` 에 `rootProject.name = 'readum'` 하나).
별도 하위 모듈로 나뉘어 있지 않다.

## 실행 모델

- **servlet + virtual thread**: servlet 기반 웹(`spring-boot-starter-webmvc`) 위에서
  `spring.threads.virtual.enabled: true`(application.yml:11) 로 요청을 virtual thread 에서 처리한다.
  블로킹 I/O(입력 moderation 동기 호출 등)를 값싸게 대량 동시 처리하기 위한 선택이다.
- **OSIV 끔**: `spring.jpa.open-in-view: false`(application.yml:7). 긴 외부 호출(AI 스트림) 동안
  DB 커넥션을 요청 내내 붙잡지 않기 위해서다.

> 위 두 결정의 배경은 [docs/record/0003](../record/0003-aichat-concurrency-moderation-http-client.md) 참조.

## 계층 구조

4-Layer 구조(`presentation` → `domain` → `model`, `infrastructure` → `domain`)를 따른다.
패키지 배치·의존 방향 규칙 상세는 [docs/conventions/package-structure.md](../conventions/package-structure.md) 참조.

## 데이터 저장

- **배포(dev)**: AWS RDS MySQL 8.4. 인프라 구성은 [docs/ops/infrastructure.md](../ops/infrastructure.md) 참조.
- **테스트**: H2 인메모리(`MODE=MySQL`, `ddl-auto: create-drop`, src/test/resources/application.yml:9).
  MySQL 전용 동작(격리 수준·gap lock 등)은 H2 로 검증되지 않는다.

## 백그라운드 처리

주기 실행은 `@Scheduled` 3개이며, `spring.task.scheduling.pool.size: 3`(application.yml:21) 으로
서로 직렬화되지 않도록 풀을 분리했다.

| 작업 | 구현 | 주기 |
|------|------|------|
| 감상문 자동 생성 적재 | `SummaryScheduler` | cron `0 0 6 * * *` (매일 06:00) |
| 감상문 생성 작업 실행(디스패치) | `SummaryJobDispatcher` | `fixedDelay ${summary-job.dispatch-interval-ms}` (기본 2000ms) |
| 생성 작업 고아 회수(리퍼) | `SummaryJobReaper` | `fixedDelay ${summary-job.reaper-interval-ms}` (기본 60000ms) |

세션 제목 자동 생성은 `@Scheduled` 가 아니라 **이벤트 기반 + 전용 스케줄러(Reactor)** 로,
채팅 영속화 풀과 격벽 분리되어 있다. 배경은 [docs/record/0001](../record/0001-aichat-title-generation-scheduler-bulkhead.md) 참조.

## 기능 → 도메인 색인

어떤 기능이 어느 도메인·패키지에 있는지는 [docs/domain/README.md](../domain/README.md) 를 참조한다.
