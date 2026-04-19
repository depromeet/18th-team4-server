package com.readwith.domain.example.service;

import com.readwith.domain.example.dto.ExampleResult;
import com.readwith.domain.example.out.ExampleSearchClient;
import com.readwith.domain.exception.ErrorCode;
import com.readwith.domain.exception.NotFoundException;
import com.readwith.model.example.repository.ExampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ExampleSearchService {

    private final ExampleRepository exampleRepository;
    private final ExampleSearchClient exampleSearchClient;

    public ExampleResult searchById(long id) {
        return exampleRepository.findById(id)
                .map(ExampleResult::from)
                .orElseThrow(() -> new NotFoundException(ErrorCode.EXAMPLE_NOT_FOUND));
    }

    public List<ExampleResult> searchAll() {
        return exampleRepository.findAll().stream()
                .map(ExampleResult::from)
                .toList();
    }

    public List<ExampleResult> searchExamples(String keyword) {
        return exampleSearchClient.execute(keyword);
    }
}
