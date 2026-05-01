package com.readum.domain.user.service;

import com.readum.domain.user.dto.CreateUserSessionResult;
import com.readum.model.user.entity.User;
import com.readum.model.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateUserSessionService {

    private final UserRepository userRepository;

    @Transactional
    public CreateUserSessionResult execute() {
        UUID sessionId = UUID.randomUUID();
        User saved = userRepository.save(User.create(sessionId));
        return new CreateUserSessionResult(saved.getId(), sessionId, saved.getCreatedAt());
    }
}
