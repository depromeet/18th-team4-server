package com.readum.presentation.controller.example;

import com.readum.domain.example.service.ExampleCreateService;
import com.readum.domain.example.service.ExampleSearchService;
import com.readum.domain.example.dto.ExampleCreateResult;
import com.readum.domain.example.dto.ExampleResult;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.controller.example.dto.ExampleCreateRequest;
import com.readum.presentation.controller.example.dto.ExampleListResponse;
import com.readum.presentation.controller.example.dto.ExampleResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "예시 (참고용)", description = "신규 기능 작성 시 구조 참조용 템플릿 컨트롤러")
@RestController
@RequestMapping("/api/v1/examples")
@RequiredArgsConstructor
public class ExampleController {

    private final ExampleCreateService exampleCreateService;
    private final ExampleSearchService exampleSearchService;

    @Operation(
            summary = "예시 생성 (참고용)",
            description = "예시 record 를 생성한다. 신규 기능 작성 시 Command 서비스 + Request DTO 구조 참조용."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공")
    })
    @PostMapping
    public ResponseEntity<GlobalApiResponse<ExampleResponse>> create(
            @RequestBody ExampleCreateRequest request) {
        ExampleCreateResult result = exampleCreateService.execute(request.toCommand());
        return GlobalApiResponse.created(ExampleResponse.from(result));
    }

    @Operation(
            summary = "예시 단건 조회 (참고용)",
            description = "id 로 예시를 조회한다. 미존재 시 404."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "404", description = "예시 미존재")
    })
    @GetMapping("/{id}")
    public ResponseEntity<GlobalApiResponse<ExampleResponse>> getById(@PathVariable long id) {
        ExampleResult result = exampleSearchService.searchById(id);
        return GlobalApiResponse.ok(ExampleResponse.from(result));
    }

    @Operation(
            summary = "예시 목록 조회 (참고용)",
            description = "전체 예시 목록을 반환한다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping
    public ResponseEntity<GlobalApiResponse<ExampleListResponse>> getAll() {
        List<ExampleResult> results = exampleSearchService.searchAll();
        return GlobalApiResponse.ok(ExampleListResponse.from(results));
    }
}
