package com.readum.domain.aiChat.out;

import com.readum.domain.aiChat.dto.AiChatStreamCommand;
import com.readum.domain.aiChat.dto.InputModerationResult;

/**
 * 사용자 입력에 대한 안전성 판별 Port.
 * 책 맥락(bookContext) 유무에 따라 카테고리 화이트리스트를 분기해 통과/차단/판별불가를 돌려준다.
 * 구현체는 외부 Moderation API 를 호출하므로 SSE 시작 전에 서비스 계층에서 동기 호출한다.
 */
public interface InputModerationClient {

    InputModerationResult check(String userText, AiChatStreamCommand.BookContext bookContext);
}
