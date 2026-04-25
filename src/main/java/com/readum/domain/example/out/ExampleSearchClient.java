package com.readum.domain.example.out;

import com.readum.domain.example.dto.ExampleResult;

import java.util.List;

public interface ExampleSearchClient {

    List<ExampleResult> execute(String keyword);
}
