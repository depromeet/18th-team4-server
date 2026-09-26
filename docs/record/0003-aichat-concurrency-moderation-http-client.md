# 0003 — AI 채팅 동시성: moderation HTTP 클라이언트 구조 + OSIV/virtual thread 전환

- **상태**: 채택
- **날짜**: 2026-07-03
- **관련**: 이슈 #103(AI 채팅 동시성 개선), [0002](0002-chat-data-scale-storage-strategy.md)
- **바뀐 코드**:
  - `src/main/resources/application.yml` — `spring.jpa.open-in-view: false`, `spring.threads.virtual.enabled: true`
  - `infrastructure/ai/openai/OpenAiHttpClientConfig` — moderation 을 blocking JDK HttpClient(HTTP/1.1, 재시도 없음)로 재구성, 채팅은 reactor(H2) 유지
  - `infrastructure/ai/openai/OpenAiHttpClientProperties` — 채팅 reactor pool 설정

## 한 줄 요약

단일 t3.micro 가 동시 100 채팅에서 붕괴하던 원인은 **moderation 호출을 reactor-netty 위에 blocking bridge 로 억지 구동**한 구조였다. 이 구조에서 부하 중 **request body 전송이 지연**되면 OpenAI 가 ~15초 timeout 으로 요청을 끊고(`PrematureCloseException`), Spring AI 기본 재시도가 이를 되먹여 **congestion collapse** 로 번졌다. 처방은 moderation 을 **blocking JDK HttpClient(HTTP/1.1, 자체 재시도 없음)** 로 옮기고 **OSIV off + virtual thread on** 을 적용하는 것. 채팅 스트리밍은 H2 를 유지한다. 이후 동시 50·100 = **100%**.

## 배경 — 증상

동시 100 채팅 부하에서 대부분 요청이 완주하지 못하고
`PrematureCloseException: Connection has been closed BEFORE response, while sending request body`
로 실패했다. 겉으론 OpenAI 연결 장애처럼 보였지만, CPU·메모리·Tomcat thread 는 모두 여유였다. "단순 외부 API 호출인데 하드웨어가 왜 못 버티나" 라는 의문에서 출발해, 배제법 + controlled A/B + 프레임 로그 분석으로 뿌리까지 규명했다.

## 근본 원인

층층이 가려진 천장 3개가 있었고, 하나를 뚫으면 다음이 드러났다.

1. **OSIV + DB connection pool 고갈** — OSIV(Open Session In View)가 요청마다 DB connection 을 요청 전체(수십 초 스트림 포함) 동안 붙잡아, 동시 요청이 pool 크기에 닿으면 고갈됐다.
2. **Tomcat platform thread pool 고갈** — 동기 moderation 호출이 blocking 이라 요청 thread 를 오래 점유했다.
3. **핵심 — moderation 의 request body starvation.** 이게 진짜 뿌리다.

### 3번의 메커니즘 (프레임 로그로 확정)

- moderation 은 `RestClient` 를 reactor-netty(`ReactorClientHttpRequestFactory`) 위에 **blocking bridge** 로 얹어 동기 호출했다. 요청 하나는 `boundedElastic` scheduler(cap 20) 로 offload 되어 직렬화됐다.
- HTTP 요청 전송은 원자적 동작이 아니라 **① 헤더 전송(스트림 open) → ② request body 전송** 두 단계다. 정상 부하에선 두 단계가 ~100ms 안에 붙어서 끝난다.
- 부하가 깊어진 시점(boundedElastic 포화 + 재시도 축적)에 열린 요청은, 헤더는 나가지만 **body 전송 단계가 스케줄을 못 받아** 지연됐다. reactor-netty 는 body 를 reactive publisher 로 구동하는데, 그 구동이 congestion 아래에서 굶었다.
- OpenAI edge(Cloudflare)는 request body 를 **약 15.0초**(측정상 15.01~15.03s 로 극히 일정) 기다린 뒤 미완성 요청을 끊는다:
  - H2: `INBOUND RST_STREAM errorCode=1 (PROTOCOL_ERROR)` — 연결은 살아있고 stream 만 리셋.
  - HTTP/1.1: body 없이 15초 후 connection 종료.
- 이 실패가 Spring AI 기본 재시도(10회 exponential backoff)를 유발 → 이미 포화된 경로에 재투입 → 더 많은 timeout → 더 많은 재시도. 이 positive feedback 이 **congestion collapse** 다.
- **프로토콜은 원인이 아니다.** H2·HTTP/1.1 둘 다 동일하게 붕괴했다(각 ~140 실패). 문제는 프로토콜이 아니라 **reactor-netty 를 blocking 으로 억지 구동한 클라이언트 구조**다. body starvation 은 프로토콜과 무관하다.

## 결정

| 대상 | 결정 | 근거 |
|---|---|---|
| **moderation 클라이언트** | reactor blocking bridge → **blocking JDK HttpClient** | JDK HttpClient 는 호출 thread 에서 헤더+body 를 한 번의 동기 작업으로 전송한다. 스트림을 body 미전송 상태로 방치하는 단계가 없어 15초 timeout 이 발생할 여지 자체가 사라진다. |
| moderation 프로토콜 | **HTTP/1.1 고정** | moderation 은 단순 1회성 요청/응답이라 H2 multiplexing 이점이 없다. H2 stack(stream multiplexing·flow control)을 배제해 guardrail 경로를 단순·예측 가능하게 둔다. |
| moderation 재시도 | **자체 재시도 없음**(`maxRetries=0`) | 재시도는 congestion collapse 의 증폭기였다. 근본을 고쳤으므로 재시도로 가릴 실패가 없고, 남는 드문 일시적 실패는 `failurePolicy(CLOSED)` 로 **503 fail-fast** 시킨다. |
| **OSIV** | **off** (`open-in-view: false`) | 긴 외부 호출(스트림) 동안 DB connection 을 붙잡지 않는다. 엔티티에 JPA 연관관계가 **0개**라 트랜잭션 밖 LAZY 접근이 없어 `LazyInitializationException` 위험이 없다. |
| **virtual thread** | **on** (`threads.virtual.enabled: true`) | blocking I/O(입력 moderation 동기 호출 등)를 값싸게 대량 동시 처리한다. Java 25 라 `synchronized` pinning 은 사실상 해소됐다. |
| **채팅 스트리밍** | reactor + **H2 유지** | 긴 스트리밍 요청은 H2 multiplexing 으로 connection 수·local port·TLS handshake 비용을 줄인다. 여기선 reactor 를 제대로(non-blocking streaming) 쓰므로 위 문제 없음. |

## 버린 대안

- **boundedElastic pool 크기 상향(예: 20→100).** 요청 수 이상으로 키우면 직렬화가 사라져 실제로 100% 가 된다(측정 확인). 하지만 이는 **증상 완화**다 — "요청 수 이상" 이라는 magic number 를 트래픽 증가마다 다시 잡아야 하고, boundedElastic 은 다른 reactive offload 와 공유하는 전역 자원이라 무한정 못 키운다. 근본은 클라이언트 구조 교체.
- **reactor-netty 유지 + 재시도만 축소.** 재시도는 증폭기일 뿐 씨앗이 아니다. 재시도를 줄여도 body starvation 자체는 남는다.
- **idle-evict / H2 on-off 튜닝.** controlled A/B 로 둘 다 실패와 무관함을 확인했다(idle-evict on/off 동일, H2/HTTP1.1 동일). 잘못된 가설이었다.
- **H2 를 원인으로 보고 회피.** H2 는 원인이 아니다(HTTP/1.1 도 동일 붕괴). moderation 을 JDK 로 옮긴 것은 "H2 회피" 가 아니라 "reactor blocking bridge 회피" 다.

## 검증 (핵심만)

1. **OSIV on/off A/B (N=40, 다른 조건 고정):** on = 성공률 52% / off = **100%**. → 천장 1(DB connection pool 고갈) 확인.
2. **reactor vs JDK moderation A/B (N=100, 동일 조건):** 성공률 = reactor **0%**(100 요청 전부 실패) / JDK **100%**. 성공률과 별개 축의 지표로, 그 아래 moderation `PrematureClose`(RST) 발생 건수 = reactor **60** / JDK **0** — 이 60 은 *붕괴 메커니즘의 신호*이지 실패한 요청 수가 아니다(재시도로 부풀 수 있어 요청 수와 1:1 아님; reactor 의 실제 실패 요청 100건 내역은 client HttpTimeout 83 + STALL 17). boundedElastic 직렬화는 양쪽 동일한데 결과가 갈렸다 → 근원은 boundedElastic 이 아니라 **클라이언트**.
3. **프레임·바이트 로그 분석:** 실패 = 헤더만 전송하고 **body 없이 ~15.0초 후** OpenAI 가 끊음(H2 = RST_STREAM `PROTOCOL_ERROR`, HTTP/1.1 = connection 종료). 성공은 `헤더 → body → 응답`. → 메커니즘 = request body starvation, 프로토콜 무관 동일.
4. **최종 config (JDK + HTTP/1.1 + 무재시도, OSIV off + VT on, 그 외 전부 기본값) N=50·100 = 100%**, 재시도 0, PrematureClose 0. 무재시도라 raw 성공률 100% = 재시도로 가릴 실패가 애초에 없음 = 근본 확정.

> 부하 하네스는 실제 배포 jar 를 Docker `cpus=2/mem=1g`(t3.micro 동일)로 띄우고 실제 OpenAI(dev key)를 호출하는 방식. throwaway 라 저장소에 커밋하지 않는다.

## 후속 (블로커 아님)

- **전역 admission control.** virtual thread on 은 Tomcat thread pool(기본 200)이라는 암묵적 동시성 상한을 없앤다. 스파이크 시 동시 채팅이 무제한으로 열려 OpenAI 예산(RPM/TPM)을 두들길 수 있다. 현재 per-user/IP rate limit 이 부분 방어를 하지만 전역 상한은 없다. 별도 과제.
- **서버 순수 천장 측정.** 현재 바인딩 제약은 **OpenAI TPM 예산**(이 계정 gpt-4o-mini 200,000 TPM → 동시 ~100~150)이라, 서버 자체의 천장은 이 예산에 가려 미측정이다. 필요 시 mock OpenAI 로 예산 제약을 제거하고 서버 순수 천장을 잰다.
- **H2 도입 판단(채팅 외 확대).** H2 의 실질 근거는 connection 수가 병목이 될 때인데, 지금은 OpenAI TPM 이 먼저 걸려 그 지점에 도달하지 못한다. 재검토는 per-IP connection 한도를 측정으로 확인했을 때.
