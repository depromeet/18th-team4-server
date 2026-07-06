package com.readum.infrastructure.ai.openai.moderation;

import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.InputModerationResult;
import com.readum.domain.aiChat.out.InputModerationClient;
import com.readum.infrastructure.ai.openai.guardrail.GuardrailProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.moderation.Categories;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.ai.moderation.ModerationPrompt;
import org.springframework.ai.moderation.ModerationResponse;
import org.springframework.ai.moderation.ModerationResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * OpenAI Moderation API 기반 입력 가드레일 Adapter.
 *
 * 책 맥락(bookContext) 유무로 카테고리 화이트리스트를 분기한다.
 * 1) API 호출 → flagged 카테고리 수집.
 * 2) flagged 중 alwaysBlock 에 하나라도 속하면 차단(맥락 무관).
 * 3) bookContext 가 있고 flagged 가 모두 relaxed 에만 속하면 통과(독서 토론 정상 질문).
 * 4) 그 외 flagged 가 있으면 차단(맥락 없음 / relaxed 밖 카테고리).
 *
 * 외부 API 장애 시 failurePolicy 로 분기한다(CLOSED: UNAVAILABLE, OPEN: PASS).
 *
 * 주의: Moderation API 에 책 줄거리를 첨부하지 않는다 — 분류기라 줄거리를 보내면 violence 점수가 오히려 오른다.
 */
@Slf4j
public class OpenAiInputModerationClientImpl implements InputModerationClient {

    /**
     * 카테고리 이름 ↔ {@link Categories} getter 매핑의 정본.
     * yml(GuardrailProperties.Moderation) 에 적는 카테고리 이름은 반드시 이 키 중 하나여야 하며,
     * 아니면 부팅이 fail-fast 한다(설정 오타로 인한 조용한 무시 방지).
     *
     * Spring AI 2.0.0-M4 의 Categories 에는 OpenAI 의 illicit / illicit-violent getter 가 없다.
     * 범죄·위험 콘텐츠는 dangerous-and-criminal-content(isDangerousAndCriminalContent) 로 표현된다.
     */
    private static final Map<String, Predicate<Categories>> CATEGORY_REGISTRY = buildRegistry();

    private static Map<String, Predicate<Categories>> buildRegistry() {
        Map<String, Predicate<Categories>> registry = new LinkedHashMap<>();
        registry.put("sexual", Categories::isSexual);
        registry.put("sexual-minors", Categories::isSexualMinors);
        registry.put("hate", Categories::isHate);
        registry.put("hate-threatening", Categories::isHateThreatening);
        registry.put("harassment", Categories::isHarassment);
        registry.put("harassment-threatening", Categories::isHarassmentThreatening);
        registry.put("self-harm", Categories::isSelfHarm);
        registry.put("self-harm-intent", Categories::isSelfHarmIntent);
        registry.put("self-harm-instructions", Categories::isSelfHarmInstructions);
        registry.put("violence", Categories::isViolence);
        registry.put("violence-graphic", Categories::isViolenceGraphic);
        registry.put("dangerous-and-criminal-content", Categories::isDangerousAndCriminalContent);
        registry.put("health", Categories::isHealth);
        registry.put("financial", Categories::isFinancial);
        registry.put("law", Categories::isLaw);
        registry.put("pii", Categories::isPii);
        return registry;
    }

    private final ModerationModel moderationModel;
    private final Set<String> alwaysBlockCategories;
    private final Set<String> bookContextRelaxedCategories;
    private final GuardrailProperties.Moderation.FailurePolicy failurePolicy;

    public OpenAiInputModerationClientImpl(ModerationModel moderationModel, GuardrailProperties guardrailProperties) {
        this.moderationModel = moderationModel;
        GuardrailProperties.Moderation moderation = guardrailProperties.moderation();
        this.alwaysBlockCategories = Set.copyOf(moderation.alwaysBlockCategories());
        this.bookContextRelaxedCategories = Set.copyOf(moderation.bookContextRelaxedCategories());
        this.failurePolicy = moderation.failurePolicy();
    }

    /**
     * 부팅 검증 + 설정 감사 로그.
     * - 알 수 없는 카테고리 이름이 설정에 있으면 부팅 실패(조용한 무시 방지).
     * - 두 카테고리 목록의 교집합이 공집합이 아니면 부팅 실패(정책 모순).
     */
    @PostConstruct
    void validateAndLogConfig() {
        List<String> unknown = Stream.concat(alwaysBlockCategories.stream(), bookContextRelaxedCategories.stream())
                .filter(name -> !CATEGORY_REGISTRY.containsKey(name))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "알 수 없는 moderation 카테고리 이름: " + unknown
                            + ". 지원하는 카테고리: " + CATEGORY_REGISTRY.keySet());
        }

        List<String> intersection = alwaysBlockCategories.stream()
                .filter(bookContextRelaxedCategories::contains)
                .toList();
        if (!intersection.isEmpty()) {
            throw new IllegalStateException(
                    "alwaysBlockCategories 와 bookContextRelaxedCategories 의 교집합이 비어있지 않습니다: " + intersection
                            + ". 한 카테고리는 항상 차단 또는 맥락-완화 중 하나에만 속해야 합니다.");
        }

        log.info("[Guardrail] InputModerationClient 설정 - alwaysBlock={}, bookContextRelaxed={}, failurePolicy={}",
                alwaysBlockCategories, bookContextRelaxedCategories, failurePolicy);
    }

    @Override
    public InputModerationResult check(String userText, AiChatStreamCommand.BookContext bookContext) {
        if (userText == null || userText.isBlank()) {
            return InputModerationResult.passed();
        }

        List<String> flagged;
        try {
            flagged = callAndCollectFlagged(userText);
        } catch (Exception e) {
            return onApiFailure(e);
        }

        return classify(flagged, bookContext, userText.length());
    }

    private List<String> callAndCollectFlagged(String userText) {
        ModerationResponse response = moderationModel.call(new ModerationPrompt(userText));
        ModerationResult result = Optional.ofNullable(response.getResult())
                .map(generation -> generation.getOutput())
                .map(moderation -> moderation.getResults())
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .orElse(null);

        if (result == null || !result.isFlagged()) {
            return List.of();
        }
        Categories categories = result.getCategories();
        if (categories == null) {
            return List.of();
        }

        List<String> flagged = new ArrayList<>();
        CATEGORY_REGISTRY.forEach((name, predicate) -> {
            if (predicate.test(categories)) {
                flagged.add(name);
            }
        });
        // flagged=true 인데 우리가 매핑한 getter 로는 잡히지 않는 경우(이 Spring AI 버전이 모르는 카테고리).
        // 분류 정보가 없으므로 안전하게 차단으로 흘려보내기 위해 sentinel 을 남긴다.
        if (flagged.isEmpty()) {
            log.warn("[Guardrail] moderation flagged=true 이나 매핑된 카테고리가 없습니다(버전 미지원 카테고리 추정). 차단 처리.");
            flagged.add("unmapped");
        }
        return flagged;
    }

    private InputModerationResult onApiFailure(Exception e) {
        if (failurePolicy == GuardrailProperties.Moderation.FailurePolicy.OPEN) {
            log.warn("[Guardrail] InputModerationClient API failure (fail-open, policy=OPEN): {}", e.getMessage());
            return InputModerationResult.passed();
        }
        log.error("[Guardrail] InputModerationClient API failure (fail-closed, policy=CLOSED)", e);
        return InputModerationResult.serviceUnavailable(e.getMessage());
    }

    private InputModerationResult classify(List<String> flagged, AiChatStreamCommand.BookContext bookContext, int length) {
        if (flagged.isEmpty()) {
            log.debug("[Guardrail] input passed (no flagged categories), length={}", length);
            return InputModerationResult.passed();
        }

        List<String> alwaysHit = flagged.stream().filter(alwaysBlockCategories::contains).toList();
        if (!alwaysHit.isEmpty()) {
            log.info("[Guardrail] input blocked action=ALWAYS_BLOCK categories={} length={}", alwaysHit, length);
            return InputModerationResult.blocked(flagged);
        }

        boolean hasBookContext = bookContext != null;
        boolean allRelaxed = hasBookContext && bookContextRelaxedCategories.containsAll(flagged);
        if (allRelaxed) {
            log.info("[Guardrail] input relaxed action=RELAXED->PASS categories={} length={}", flagged, length);
            return InputModerationResult.passed();
        }

        log.info("[Guardrail] input blocked action=BLOCK categories={} bookContext={} length={}",
                flagged, hasBookContext, length);
        return InputModerationResult.blocked(flagged);
    }
}
