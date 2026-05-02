package com.readum.presentation.controller.user;

import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.user.userbook.dto.UserBookCreateResult;
import com.readum.domain.user.userbook.service.UserBookCreateService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.user.dto.UserBookCreateRequest;
import com.readum.presentation.controller.user.dto.UserBookResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Tag(name = "내 책장", description = "사용자가 등록한 도서(책장) 관리")
@RestController
@RequestMapping("/api/v1/user-books")
@RequiredArgsConstructor
public class UserBookController {

    private final UserBookCreateService userBookCreateService;

    @Operation(
            summary = "내 책장 도서 추가",
            description = "외부 도서 ID(bookExternalId)를 기반으로 도서를 조회하거나 신규 등록한 뒤, 로그인한 사용자의 책장에 추가합니다. " +
                    "동일 도서가 이미 책장에 존재하면 409 Conflict를 반환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "도서 추가 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "요청 값 검증 실패 (bookExternalId 또는 title 누락 등)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "이미 책장에 등록된 도서")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<UserBookResponse>> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody UserBookCreateRequest request) {
        if (userId == null) {
            throw new UnauthorizedException(AuthErrorCode.INVALID_TOKEN);
        }
        UserBookCreateResult result = userBookCreateService.execute(request.toCommand(userId));
        UserBookResponse response = UserBookResponse.from(result);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .location(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(result.id())
                        .toUri())
                .body(new ApiResponse<>(response, null));
    }
}
