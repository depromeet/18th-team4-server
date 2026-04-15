package com.readwith.model.example.repository;

import com.readwith.model.example.entity.ExampleEntity;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JPA 도입 전까지 사용하는 인메모리 구현체.
 * JPA 추가 시 이 클래스를 제거하고 JpaRepository를 확장하면 됩니다.
 */
@Repository
public class InMemoryExampleRepository implements ExampleRepository {

    private final Map<Long, ExampleEntity> store = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1);

    @Override
    public ExampleEntity save(ExampleEntity entity) {
        long id = sequence.getAndIncrement();
        ExampleEntity saved = ExampleEntity.of(id, entity.getName(), entity.getDescription(), entity.getCreatedAt());
        store.put(id, saved);
        return saved;
    }

    @Override
    public Optional<ExampleEntity> findById(Long id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<ExampleEntity> findAll() {
        return new ArrayList<>(store.values());
    }
}
