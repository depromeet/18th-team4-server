package com.readwith.presentation.controller.example;

import com.readwith.domain.example.service.ExampleCreateService;
import com.readwith.domain.example.service.ExampleSearchService;
import com.readwith.domain.example.dto.ExampleCreateResult;
import com.readwith.domain.example.dto.ExampleResult;
import com.readwith.presentation.common.ApiResponse;
import com.readwith.presentation.controller.example.dto.ExampleCreateRequest;
import com.readwith.presentation.controller.example.dto.ExampleListResponse;
import com.readwith.presentation.controller.example.dto.ExampleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/examples")
@RequiredArgsConstructor
public class ExampleController {

    private final ExampleCreateService exampleCreateService;
    private final ExampleSearchService exampleSearchService;

    @PostMapping
    public ResponseEntity<ApiResponse<ExampleResponse>> create(
            @RequestBody ExampleCreateRequest request) {
        ExampleCreateResult result = exampleCreateService.execute(request.toCommand());
        return ApiResponse.created(ExampleResponse.from(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ExampleResponse>> getById(@PathVariable long id) {
        ExampleResult result = exampleSearchService.searchById(id);
        return ApiResponse.ok(ExampleResponse.from(result));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<ExampleListResponse>> getAll() {
        List<ExampleResult> results = exampleSearchService.searchAll();
        return ApiResponse.ok(ExampleListResponse.from(results));
    }
}
