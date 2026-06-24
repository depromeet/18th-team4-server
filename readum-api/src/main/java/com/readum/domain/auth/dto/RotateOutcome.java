package com.readum.domain.auth.dto;

public enum RotateOutcome {
    ROTATED,
    GRACE_HIT,
    NOT_FOUND,
    EXPIRED,
    REUSE_DETECTED
}
