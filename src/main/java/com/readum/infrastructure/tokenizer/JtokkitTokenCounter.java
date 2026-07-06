package com.readum.infrastructure.tokenizer;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.readum.domain.aiChat.out.TokenCounter;
import org.springframework.stereotype.Component;

/** jtokkit(o200k_base) 기반 토큰 계산. Encoding 은 스레드 안전하며 생성 비용이 있어 1회만 만든다. */
@Component
public class JtokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    public JtokkitTokenCounter() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.O200K_BASE);
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }
}
