package com.readwith.domain.example.service;

import com.readwith.domain.example.dto.ExampleCreateCommand;
import com.readwith.domain.example.dto.ExampleCreateResult;
import com.readwith.model.example.entity.ExampleEntity;
import com.readwith.model.example.repository.ExampleRepository;
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
