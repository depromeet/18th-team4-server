package com.readum.presentation.controller.userbook;

import com.readum.presentation.common.ApiResponse;
import com.readum.presentation.controller.userbook.dto.UserBookCreateRequest;
import com.readum.presentation.controller.userbook.dto.UserBookResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/user-books")
@RequiredArgsConstructor
public class UserBookController {

    @PostMapping
    public ResponseEntity<ApiResponse<UserBookResponse>> create(
            @Valid @RequestBody UserBookCreateRequest request) {
        // TODO: 인증된 사용자 ID 주입 및 UserBookCreateService 연동
        UserBookResponse response = null; // 추후 서비스 결과로 교체

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/user-books/" + null)) // 추후 result.id() 로 교체
                .body(new ApiResponse<>(response, null));
    }
}
