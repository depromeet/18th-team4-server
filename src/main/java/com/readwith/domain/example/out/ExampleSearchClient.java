package com.readwith.domain.example.out;

import com.readwith.domain.example.dto.ExampleResult;

import java.util.List;

public interface ExampleSearchClient {

    List<ExampleResult> execute(String keyword);
}
