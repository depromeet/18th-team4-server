package com.readum.model.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "`user`")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", unique = true, length = 255)
    private String deviceId;

    @Column(name = "session_id", nullable = false, unique = true, length = 36)
    private String sessionId;

    // 기존 사용자 데이터에는 닉네임이 없을 수 있어 nullable 허용. 신규 가입부터 서버가 임의 배정한다.
    @Column(name = "nickname", length = 10)
    private String nickname;

    @Column(name = "last_selected_user_book_id")
    private Long lastSelectedUserBookId;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static User create(UUID sessionId, String nickname) {
        LocalDateTime now = LocalDateTime.now();
        return new User(null, null, sessionId.toString(), nickname, null, false, now, now);
    }

    public void completeOnboarding() {
        this.onboardingCompleted = true;
        this.updatedAt = LocalDateTime.now();
    }

    public void selectBook(Long userBookId) {
        this.lastSelectedUserBookId = userBookId;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
        this.updatedAt = LocalDateTime.now();
    }
}
