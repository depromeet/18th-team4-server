package com.readum.model.example.repository;

import com.readum.model.example.entity.ExampleEntity;

import java.util.List;
import java.util.Optional;

public interface ExampleRepository {

    ExampleEntity save(ExampleEntity entity);

    Optional<ExampleEntity> findById(Long id);

    List<ExampleEntity> findAll();
}
