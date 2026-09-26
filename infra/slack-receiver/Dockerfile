# 빌드 단계
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/slack-receiver .

# 실행 단계 — 정적 바이너리 하나만
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/slack-receiver /slack-receiver
EXPOSE 8090
USER nonroot:nonroot
ENTRYPOINT ["/slack-receiver"]
