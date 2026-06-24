package com.readum.domain.example.service;

import com.readum.domain.example.dto.ExampleCreateCommand;
import com.readum.domain.example.dto.ExampleCreateResult;
import com.readum.model.example.entity.ExampleEntity;
import com.readum.model.example.repository.ExampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ExampleCreateService {

    private final ExampleRepository exampleRepository;

    public ExampleCreateResult execute(ExampleCreateCommand command) {
        ExampleEntity entity = ExampleEntity.create(
                command.name(),
                command.description()
        );
        ExampleEntity saved = exampleRepository.save(entity);
        return new ExampleCreateResult(saved.getId(), saved.getName());
    }
}
