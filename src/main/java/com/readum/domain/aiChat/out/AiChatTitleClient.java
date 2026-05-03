package com.readum.domain.aiChat.out;

/**
 * 채팅 세션 제목 생성을 위한 LLM 호출 port.
 * streaming 이 아니라 단발 비동기 호출이며, 메인 채팅용 {@link AiChatClient} 와는
 * 시스템 프롬프트·응답 형태가 달라 별도 port 로 분리한다.
 */
public interface AiChatTitleClient {

    String generate(String firstUserMessage);
}
