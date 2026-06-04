package com.docversion.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 버전 메타데이터 JSON 처리. C++의 escapeJsonString/buildVersionMetadataJson/parseJson을
 * Jackson ObjectMapper로 대체(유형 2: 라이브러리로 해소).
 * <p>C++는 수동 문자열 조립 + 평면 파서였으나, Jackson은 중첩/이스케이프를 모두 처리하므로
 * 추후 DLP 메타데이터(dlp.*) 확장에도 안전.
 */
@Component
public class VersionMetadata {

    private final ObjectMapper objectMapper;

    public VersionMetadata(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** C++ buildVersionMetadataJson: 최초 author 필드만 포함. */
    public String buildInitial(String userId) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("author", userId);
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException("metadata 직렬화 실패", e);
        }
    }

    /** C++ parseJson 대체: 평면 문자열 맵으로 역직렬화. null/공백은 빈 맵. */
    public Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return new LinkedHashMap<>(); // 손상된 데이터는 빈 맵(C++ fallback과 동일 취지)
        }
    }
}
