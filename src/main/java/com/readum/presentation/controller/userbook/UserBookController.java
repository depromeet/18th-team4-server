package com.readum.presentation.controller.userbook;

import com.readum.domain.auth.exception.AuthErrorCode;
import com.readum.domain.exception.UnauthorizedException;
import com.readum.domain.userbook.dto.UserBookCreateResult;
import com.readum.domain.userbook.service.UserBookCreateService;
import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.userbook.dto.UserBookCreateRequest;
import com.readum.presentation.controller.userbook.dto.UserBookResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/user-books")
@RequiredArgsConstructor
public class UserBookController {

    private final UserBookCreateService userBookCreateService;

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
                .location(URI.create("/api/v1/user-books/" + result.id()))
                .body(new ApiResponse<>(response, null));
    }
}
