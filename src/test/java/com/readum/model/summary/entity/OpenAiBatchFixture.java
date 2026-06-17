package com.readum.model.summary.entity;

import com.readum.support.TestOnly;

import java.time.LocalDateTime;

/**
 * 특정 상태의 {@link OpenAiBatch} 를 만드는 명명 팩토리. 같은 패키지의 package-private 전체필드 생성자 호출.
 */
@TestOnly
public final class OpenAiBatchFixture {

    private OpenAiBatchFixture() {
    }

    /** 저장된 SUBMITTED 배치. id·batchId·jobCount 만 달라지므로 그것만 인자로 받는다. */
    public static OpenAiBatch submitted(Long id, String batchId, int jobCount) {
        LocalDateTime now = LocalDateTime.now();
        return new OpenAiBatch(
                id, batchId, OpenAiBatch.Status.SUBMITTED, "file_in_" + id, null, null, jobCount, now, now
        );
    }
}
