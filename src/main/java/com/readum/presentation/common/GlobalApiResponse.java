package com.readum.presentation.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GlobalApiResponse<T>(T data, ErrorBody error) {

    public record ErrorBody(String message) {}

    public static <T> ResponseEntity<GlobalApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(new GlobalApiResponse<>(data, null));
    }

    public static <T> ResponseEntity<GlobalApiResponse<T>> created(T data) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new GlobalApiResponse<>(data, null));
    }

    public static ResponseEntity<GlobalApiResponse<?>> error(HttpStatus status, String message) {
        return ResponseEntity
                .status(status)
                .body(new GlobalApiResponse<>(null, new ErrorBody(message)));
    }
}
