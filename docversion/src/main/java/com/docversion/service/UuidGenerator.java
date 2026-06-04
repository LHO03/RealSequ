package com.docversion.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 식별자 발급기. C++ generateUUID()를 대체.
 * <p>C++는 "uuid_{ts}_{counter}" 유사 UUID를 썼으나(동시성 취약),
 * Java에서는 표준 {@link UUID#randomUUID()}(36자, CHAR(36) 적합)로 대체한다.
 * fileId/versionId 모두 의미 없는 식별자 — 경로/시간 추론 금지(05/18 ID 정책).
 */
@Component
public class UuidGenerator {

    public String newId() {
        return UUID.randomUUID().toString();
    }
}
