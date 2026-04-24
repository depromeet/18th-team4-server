package com.readwith.domain.ai.service;

import com.readwith.domain.ai.dto.AiChatCommand;
import com.readwith.domain.ai.out.AiChatClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
@RequiredArgsConstructor
public class AiStreamChatService {

    private static final String EVENT_MESSAGE = "message";
    private static final String EVENT_ERROR = "error";
    private static final String ERROR_MESSAGE = "통신 중 문제가 발생했습니다.";

    private final AiChatClient aiChatClient;

    public Flux<ServerSentEvent<String>> stream(AiChatCommand command) {
        return aiChatClient.stream(command)
                .map(text -> ServerSentEvent.<String>builder()
                        .event(EVENT_MESSAGE)
                        .data(text)
                        .build())
                .onErrorResume(e -> Flux.just(
                        ServerSentEvent.<String>builder()
                                .event(EVENT_ERROR)
                                .data(ERROR_MESSAGE)
                                .build()
                ));
    }
}
