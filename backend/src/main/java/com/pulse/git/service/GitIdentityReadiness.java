package com.pulse.git.service;

public record GitIdentityReadiness(
        boolean required,
        boolean ready,
        String code,
        String message,
        String authorName,
        String authorEmail
) {}
