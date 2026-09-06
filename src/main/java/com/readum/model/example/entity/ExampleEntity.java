package com.readum.model.example.entity;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ExampleEntity {

    private Long id;
    private String name;
    private String description;
    private LocalDateTime createdAt;

    public static ExampleEntity create(String name, String description) {
        return new ExampleEntity(null, name, description, LocalDateTime.now());
    }

    public static ExampleEntity of(Long id, String name, String description, LocalDateTime createdAt) {
        return new ExampleEntity(id, name, description, createdAt);
    }
}
