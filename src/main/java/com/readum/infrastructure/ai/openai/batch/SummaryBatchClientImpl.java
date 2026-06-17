package com.readum.infrastructure.ai.openai.batch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.readum.domain.aiChat.dto.SummaryDraftResult;
import com.readum.domain.summary.dto.SummaryBatchRequestItem;
import com.readum.domain.summary.dto.SummaryBatchResultItem;
import com.readum.domain.summary.out.SummaryBatchClient;
import com.readum.infrastructure.ai.openai.SummaryPromptAssembler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Batch API 를 통해 감상문 초안 생성을 일괄 제출·폴링·수집하는 어댑터.
 *
 * <p>Spring AI 의 {@code ChatClient} 는 Batch API 를 노출하지 않으므로,
 * {@link RestClient} 로 OpenAI REST 엔드포인트를 직접 호출한다.
 *
 * <ul>
 *   <li>{@link #submit} — JSONL 입력 파일 업로드 + batch 생성.
 *   <li>{@link #pollStatus} — batch 처리 상태 조회.
 *   <li>{@link #fetchResults} — 완료된 batch 의 결과 파일 다운로드 + 파싱.
 * </ul>
 *
 * <p>시스템 프롬프트·대화 이력 포맷·응답 스키마는 {@link SummaryPromptAssembler} 에서 가져온다.
 * 동기 단건 경로({@code AiSummaryClientImpl})와 동일한 내용을 쓴다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(OpenAiBatchProperties.class)
public class SummaryBatchClientImpl implements SummaryBatchClient {

    // OpenAI Batch API 경로
    private static final String FILES_PATH = "/files";
    private static final String BATCHES_PATH = "/batches";
    // Batch API 요청 엔드포인트 (JSONL 의 "url" 필드)
    private static final String CHAT_COMPLETIONS_ENDPOINT = "/v1/chat/completions";
    // 파일 업로드 목적 (purpose 필드)
    private static final String FILE_PURPOSE_BATCH = "batch";
    // JSONL 파일 이름 (OpenAI 측 식별용으로만 사용)
    private static final String JSONL_FILENAME = "summary_batch_input.jsonl";

    private final RestClient restClient;
    private final SummaryPromptAssembler promptAssembler;
    private final OpenAiBatchProperties properties;
    private final ObjectMapper objectMapper;

    public SummaryBatchClientImpl(
            OpenAiBatchProperties properties,
            SummaryPromptAssembler promptAssembler,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.promptAssembler = promptAssembler;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    // -------------------------------------------------------------------------
    // SummaryBatchClient 구현
    // -------------------------------------------------------------------------

    /**
     * 항목 목록을 JSONL 로 직렬화하고, OpenAI Files API 에 업로드한 뒤 Batch API 에 제출한다.
     *
     * @return batchId (OpenAI batch 식별자) 와 inputFileId (업로드된 입력 파일 식별자)
     */
    @Override
    public BatchSubmission submit(List<SummaryBatchRequestItem> items) {
        byte[] jsonlBytes = buildJsonlBytes(items);
        String inputFileId = uploadFile(jsonlBytes);
        String batchId = createBatch(inputFileId);
        log.info("감상문 batch 제출 완료 — 항목 수={} batchId={} inputFileId={}", items.size(), batchId, inputFileId);
        return new BatchSubmission(batchId, inputFileId);
    }

    /**
     * OpenAI Batch API 에서 현재 처리 상태를 조회하고, 우리 상태 모델로 변환해 반환한다.
     *
     * <p>상태 매핑:
     * <ul>
     *   <li>{@code completed} → {@link BatchStatus.State#COMPLETED}
     *   <li>{@code failed} / {@code expired} / {@code cancelled} / {@code cancelling}
     *       → {@link BatchStatus.State#FAILED}
     *   <li>그 외 ({@code validating} / {@code in_progress} / {@code finalizing})
     *       → {@link BatchStatus.State#RUNNING}
     * </ul>
     */
    @Override
    public BatchStatus pollStatus(String batchId) {
        OpenAiBatchResponse response = restClient.get()
                .uri(BATCHES_PATH + "/{batchId}", batchId)
                .retrieve()
                .body(OpenAiBatchResponse.class);

        if (response == null) {
            log.error("감상문 batch 상태 조회 응답 없음 batchId={}", batchId);
            throw new RestClientException("OpenAI batch 상태 조회 응답이 없습니다. batchId=" + batchId);
        }

        BatchStatus.State state = switch (response.status()) {
            case "completed" -> BatchStatus.State.COMPLETED;
            case "failed", "expired", "cancelled", "cancelling" -> BatchStatus.State.FAILED;
            default -> BatchStatus.State.RUNNING;
        };

        log.info("감상문 batch 상태 조회 — batchId={} openAiStatus={} 내부상태={}",
                batchId, response.status(), state);
        return new BatchStatus(batchId, state, response.output_file_id(), response.error_file_id());
    }

    /**
     * 완료된 batch 의 결과 파일(output_file_id)과 오류 파일(error_file_id)을 다운로드해
     * {@link SummaryBatchResultItem} 목록으로 파싱한다.
     *
     * <p>출력 파일 각 줄:
     * <ul>
     *   <li>HTTP 200 응답 → {@code choices[0].message.content} 를 {@link SummaryDraftResult} 로 파싱 → success 항목
     *   <li>HTTP 429 또는 5xx → retryable=true failure 항목
     *   <li>그 외 4xx → retryable=false failure 항목
     * </ul>
     *
     * <p>오류 파일 각 줄은 요청 수준 검증 오류이므로 retryable=false 로 처리한다.
     */
    @Override
    public List<SummaryBatchResultItem> fetchResults(BatchStatus status) {
        List<SummaryBatchResultItem> resultItems = new ArrayList<>();

        if (status.outputFileId() != null) {
            String outputJsonl = downloadFileContent(status.outputFileId());
            parseOutputJsonl(outputJsonl, resultItems);
        }

        if (status.errorFileId() != null) {
            String errorJsonl = downloadFileContent(status.errorFileId());
            parseErrorJsonl(errorJsonl, resultItems);
        }

        return resultItems;
    }

    // -------------------------------------------------------------------------
    // JSONL 조립
    // -------------------------------------------------------------------------

    /**
     * 항목 목록을 OpenAI Batch API 에서 요구하는 JSONL 형식의 바이트 배열로 변환한다.
     *
     * <p>각 줄 구조:
     * <pre>
     * {"custom_id":"...", "method":"POST", "url":"/v1/chat/completions",
     *  "body":{"model":"...", "messages":[...], "response_format":{...}}}
     * </pre>
     */
    private byte[] buildJsonlBytes(List<SummaryBatchRequestItem> items) {
        StringBuilder sb = new StringBuilder();
        for (SummaryBatchRequestItem item : items) {
            Map<String, Object> line = buildJsonlLine(item);
            try {
                sb.append(objectMapper.writeValueAsString(line)).append("\n");
            } catch (Exception e) {
                throw new IllegalStateException(
                        "감상문 batch JSONL 직렬화 실패 customId=" + item.customId(), e);
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 단일 항목에 대한 JSONL 줄 구조를 {@link Map} 으로 조립한다.
     */
    private Map<String, Object> buildJsonlLine(SummaryBatchRequestItem item) {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", promptAssembler.systemPrompt()),
                Map.of("role", "user", "content", promptAssembler.buildUserMessage(item.messages()))
        );

        // response_format — JSON 스키마 모드 (strict=true, additionalProperties=false)
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string"),
                        "body", Map.of("type", "string")
                ),
                "required", List.of("title", "body"),
                "additionalProperties", false
        );
        Map<String, Object> jsonSchema = Map.of(
                "name", SummaryPromptAssembler.RESPONSE_FORMAT_SCHEMA_NAME,
                "strict", true,
                "schema", schema
        );
        Map<String, Object> responseFormat = Map.of(
                "type", "json_schema",
                "json_schema", jsonSchema
        );

        Map<String, Object> body = Map.of(
                "model", properties.model(),
                "messages", messages,
                "response_format", responseFormat
        );

        return Map.of(
                "custom_id", item.customId(),
                "method", "POST",
                "url", CHAT_COMPLETIONS_ENDPOINT,
                "body", body
        );
    }

    // -------------------------------------------------------------------------
    // Files API
    // -------------------------------------------------------------------------

    /**
     * JSONL 바이트 배열을 OpenAI Files API 에 업로드하고, 반환된 파일 식별자를 반환한다.
     *
     * <p>업로드 실패 시 {@link RestClientException} 이 전파된다.
     * 호출자(서비스 레이어)가 ERROR 로그를 남기므로 여기서는 INFO 만 기록한다.
     */
    private String uploadFile(byte[] jsonlBytes) {
        // Spring 의 ByteArrayResource 는 multipart/form-data 에서 파일명을 제공하기 위해
        // getFilename() 을 재정의해야 한다. 익명 클래스로 확장한다.
        ByteArrayResource fileResource = new ByteArrayResource(jsonlBytes) {
            @Override
            public String getFilename() {
                return JSONL_FILENAME;
            }
        };

        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("purpose", FILE_PURPOSE_BATCH);
        parts.add("file", fileResource);

        OpenAiFileResponse response = restClient.post()
                .uri(FILES_PATH)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts)
                .retrieve()
                .body(OpenAiFileResponse.class);

        if (response == null || response.id() == null) {
            throw new RestClientException("OpenAI 파일 업로드 응답이 없거나 파일 식별자가 누락되었습니다.");
        }

        log.info("감상문 batch 입력 파일 업로드 완료 — 파일 식별자={} 크기(바이트)={}", response.id(), jsonlBytes.length);
        return response.id();
    }

    // -------------------------------------------------------------------------
    // Batch API
    // -------------------------------------------------------------------------

    /**
     * 업로드된 입력 파일로 OpenAI Batch 를 생성하고, batch 식별자를 반환한다.
     */
    private String createBatch(String inputFileId) {
        Map<String, String> requestBody = Map.of(
                "input_file_id", inputFileId,
                "endpoint", CHAT_COMPLETIONS_ENDPOINT,
                "completion_window", properties.completionWindow()
        );

        OpenAiBatchResponse response = restClient.post()
                .uri(BATCHES_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(OpenAiBatchResponse.class);

        if (response == null || response.id() == null) {
            throw new RestClientException("OpenAI Batch 생성 응답이 없거나 batch 식별자가 누락되었습니다.");
        }

        return response.id();
    }

    /**
     * OpenAI Files API 에서 파일 내용(JSONL 텍스트)을 다운로드한다.
     */
    private String downloadFileContent(String fileId) {
        String content = restClient.get()
                .uri(FILES_PATH + "/{fileId}/content", fileId)
                .retrieve()
                .body(String.class);

        if (content == null) {
            log.error("감상문 batch 결과 파일 내용이 비어 있음 — 파일 식별자={}", fileId);
            return "";
        }
        return content;
    }

    // -------------------------------------------------------------------------
    // 결과 파싱
    // -------------------------------------------------------------------------

    /**
     * 출력 파일(output_file_id) JSONL 을 줄별로 파싱해 {@code resultItems} 에 추가한다.
     *
     * <p>HTTP 응답 코드:
     * <ul>
     *   <li>200 → 성공 항목 (title·body 파싱)
     *   <li>429 또는 5xx → 재시도 가능 실패 항목
     *   <li>그 외 4xx → 즉시 실패 항목
     * </ul>
     */
    private void parseOutputJsonl(String jsonl, List<SummaryBatchResultItem> resultItems) {
        for (String line : splitLines(jsonl)) {
            try {
                OpenAiOutputLine outputLine = objectMapper.readValue(line, OpenAiOutputLine.class);
                String customId = outputLine.custom_id();

                if (outputLine.response() == null) {
                    resultItems.add(SummaryBatchResultItem.failure(
                            customId, false, "BATCH_OUTPUT_MISSING_RESPONSE", "응답 객체가 없습니다."));
                    continue;
                }

                int statusCode = outputLine.response().status_code();
                if (statusCode == 200) {
                    SummaryDraftResult draftResult = parseDraftResult(customId, outputLine.response().body());
                    if (draftResult != null) {
                        resultItems.add(SummaryBatchResultItem.success(customId, draftResult));
                    } else {
                        resultItems.add(SummaryBatchResultItem.failure(
                                customId, false, "BATCH_OUTPUT_PARSE_FAILURE", "감상문 응답 파싱 실패"));
                    }
                } else {
                    // 재시도 여부: 429(호출 허용량 초과) 또는 5xx(서버 오류) 는 재시도 가능
                    boolean retryable = statusCode == 429 || statusCode >= 500;
                    String errorMessage = "HTTP " + statusCode;
                    resultItems.add(SummaryBatchResultItem.failure(
                            customId, retryable, "BATCH_HTTP_" + statusCode, errorMessage));
                }
            } catch (Exception e) {
                log.error("감상문 batch 출력 파일 줄 파싱 실패 — line={}", line, e);
                // 파싱 불가 줄은 customId 를 알 수 없으므로 건너뛴다.
            }
        }
    }

    /**
     * 오류 파일(error_file_id) JSONL 을 줄별로 파싱해 {@code resultItems} 에 추가한다.
     *
     * <p>오류 파일의 항목은 요청 수준 검증 오류이므로 retryable=false 로 처리한다.
     */
    private void parseErrorJsonl(String jsonl, List<SummaryBatchResultItem> resultItems) {
        for (String line : splitLines(jsonl)) {
            try {
                OpenAiErrorLine errorLine = objectMapper.readValue(line, OpenAiErrorLine.class);
                String customId = errorLine.custom_id();
                String code = errorLine.error() != null ? errorLine.error().code() : "BATCH_ERROR_UNKNOWN";
                String message = errorLine.error() != null ? errorLine.error().message() : "오류 파일 항목";
                resultItems.add(SummaryBatchResultItem.failure(customId, false, code, message));
            } catch (Exception e) {
                log.error("감상문 batch 오류 파일 줄 파싱 실패 — line={}", line, e);
            }
        }
    }

    /**
     * Chat Completion 응답 body 에서 {@link SummaryDraftResult} 를 추출한다.
     *
     * <p>{@code choices[0].message.content} 는 JSON 문자열 {@code {"title":"...", "body":"..."}} 이므로,
     * 한 번 더 역직렬화한다.
     */
    private SummaryDraftResult parseDraftResult(String customId, OpenAiChatCompletionBody body) {
        if (body == null || body.choices() == null || body.choices().isEmpty()) {
            log.error("감상문 batch 응답에 choices 가 없음 customId={}", customId);
            return null;
        }
        String content = body.choices().get(0).message().content();
        if (content == null || content.isBlank()) {
            log.error("감상문 batch 응답 content 가 비어 있음 customId={}", customId);
            return null;
        }
        try {
            return objectMapper.readValue(content, SummaryDraftResult.class);
        } catch (Exception e) {
            log.error("감상문 batch 응답 content JSON 파싱 실패 customId={} content={}", customId, content, e);
            return null;
        }
    }

    /**
     * JSONL 문자열을 줄 단위로 분리한다. 빈 줄은 제거한다.
     */
    private List<String> splitLines(String jsonl) {
        if (jsonl == null || jsonl.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : jsonl.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    // -------------------------------------------------------------------------
    // OpenAI 응답 매핑 record (이 어댑터 내부 전용)
    // -------------------------------------------------------------------------

    /** OpenAI Files API 업로드 응답. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiFileResponse(String id) {}

    /** OpenAI Batch API 생성/조회 응답. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiBatchResponse(
            String id,
            String status,
            String output_file_id,
            String error_file_id
    ) {}

    /** Batch 출력 파일의 한 줄. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiOutputLine(
            String custom_id,
            OpenAiOutputResponse response
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiOutputResponse(
            int status_code,
            OpenAiChatCompletionBody body
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiChatCompletionBody(List<OpenAiChoice> choices) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiChoice(OpenAiMessage message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiMessage(String content) {}

    /** Batch 오류 파일의 한 줄. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiErrorLine(
            String custom_id,
            OpenAiErrorDetail error
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiErrorDetail(String code, String message) {}
}
