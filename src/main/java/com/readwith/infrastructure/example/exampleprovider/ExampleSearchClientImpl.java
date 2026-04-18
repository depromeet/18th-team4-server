package com.readwith.infrastructure.example.exampleprovider;

import com.readwith.domain.example.dto.ExampleResult;
import com.readwith.domain.example.out.ExampleSearchClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class ExampleSearchClientImpl implements ExampleSearchClient {

    @Override
    public List<ExampleResult> execute(String keyword) {
        // TODO: 실제 외부 API 호출로 교체
        return List.of(
                new ExampleResult(999L, "External-" + keyword, "Stub result", LocalDateTime.now())
        );
    }
}
