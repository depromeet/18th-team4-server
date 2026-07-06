package com.readum.presentation.controller.user;

import com.readum.domain.userBook.dto.UserBookCreateResult;
import com.readum.domain.userBook.dto.UserBookDeleteCommand;
import com.readum.domain.userBook.dto.UserBookSearchResult;
import com.readum.domain.userBook.service.UserBookCreateService;
import com.readum.domain.userBook.service.UserBookDeleteService;
import com.readum.domain.userBook.service.UserBookSearchService;
import com.readum.presentation.common.GlobalApiResponse;
import com.readum.presentation.common.security.AuthenticatedUserId;
import com.readum.presentation.controller.user.dto.UserBookCreateRequest;
import com.readum.presentation.controller.user.dto.UserBookListResponse;
import com.readum.presentation.controller.user.dto.UserBookResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final UserBookSearchService userBookSearchService;
    private final UserBookDeleteService userBookDeleteService;

    @Operation(
            summary = "내 책장 도서 추가",
            description = "외부 도서 ID(bookExternalId)를 기반으로 도서를 조회하거나 신규 등록한 뒤, 로그인한 사용자의 책장에 추가합니다. " +
                    "동일 도서가 이미 책장에 존재하면 409 Conflict를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "도서 추가 성공"),
            @ApiResponse(responseCode = "400", description = "요청 값 검증 실패 (bookExternalId 또는 title 누락 등)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "409", description = "이미 책장에 등록된 도서")
    })
    @PostMapping
    public ResponseEntity<GlobalApiResponse<UserBookResponse>> create(
            @AuthenticatedUserId Long userId,
            @Valid @RequestBody UserBookCreateRequest request) {
        UserBookCreateResult result = userBookCreateService.execute(request.toCommand(userId));
        UserBookResponse response = UserBookResponse.from(result);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .location(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}")
                        .buildAndExpand(result.id())
                        .toUri())
                .body(new GlobalApiResponse<>(response, null));
    }

    @Operation(
            summary = "내 책장 도서 목록 조회",
            description = "현재 user_session 쿠키로 식별된 사용자가 책장에 등록한 도서 목록을 최근 등록순(user_book.createdAt desc, id desc)으로 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공 (등록 도서 0건이면 빈 배열)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청")
    })
    @GetMapping
    public ResponseEntity<GlobalApiResponse<UserBookListResponse>> list(
            @AuthenticatedUserId Long userId) {
        UserBookSearchResult result = userBookSearchService.findMyBooks(userId);
        return GlobalApiResponse.ok(UserBookListResponse.from(result));
    }

    @Operation(
            summary = "내 책장 도서 삭제",
            description = "로그인한 사용자가 자신의 책장에 등록한 도서를 삭제합니다. " +
                    "삭제 시 그 도서에 연결된 모든 AI 대화 세션·메시지와 감상 기록도 함께 영구 삭제됩니다. " +
                    "여러 사용자가 공유하는 Book 마스터 정보는 삭제되지 않습니다. " +
                    "본인이 등록하지 않았거나 존재하지 않는 도서면 404 를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공 (응답 본문 없음)"),
            @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
            @ApiResponse(responseCode = "404", description = "본인 책장에 등록되지 않은 도서")
    })
    @DeleteMapping("/{userBookId}")
    public ResponseEntity<Void> delete(
            @AuthenticatedUserId Long userId,
            @PathVariable Long userBookId) {
        userBookDeleteService.execute(new UserBookDeleteCommand(userId, userBookId));
        return ResponseEntity.noContent().build();
    }
}
