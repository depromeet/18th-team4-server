package com.readum.domain.user.service;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 신규 익명 사용자에게 배정할 닉네임을 임의로 고른다.
 * 독서 서비스 컨셉의 한글 닉네임 후보를 정적 상수로 보유하며, 중복 배정을 허용한다.
 * 후보는 모두 "영어 대/소문자 + 한글 + 숫자, 최대 10자" 제약(정규식 {@code ^[A-Za-z0-9가-힣]{1,10}$})을 만족한다.
 *
 * <p>정적 in-memory 상수에서 고르는 순수 도메인 빌딩 블록이라 외부 시스템도 교체 대상도 아니므로
 * Port/Adapter 없이 {@code @Component} 로 둔다 (UserBookConflictReader 와 동일한 선례).
 */
@Component
public class NicknameGenerator {

    // package-private: 같은 패키지 테스트가 모든 후보의 제약 충족을 결정론적으로 검증한다.
    static final List<String> NICKNAMES = List.of(
            "책읽는여우",
            "밤샘독서가",
            "문장수집가",
            "행간탐험가",
            "책갈피요정",
            "심야의서재",
            "독서하는곰",
            "글줄나그네",
            "페이지여행",
            "이야기사냥꾼"
    );

    public String generate() {
        return NICKNAMES.get(ThreadLocalRandom.current().nextInt(NICKNAMES.size()));
    }
}
