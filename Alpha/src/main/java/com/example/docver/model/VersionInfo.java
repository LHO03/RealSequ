package com.example.docver.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 의사코드 VersionInfo struct 이식.
 * 의사코드: c++17 라인 35-46
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VersionInfo {
    private String versionId;    // {fileId}.v{timestamp}_{counter}
    private String fileId;
    private String userId;        // 버전 생성자
    private long timestamp;       // Unix timestamp (초 단위)
    private long size;            // bytes
    private String mimeType;
    private String metadata;      // JSON string (Java 전환 시 차후 Jackson 객체로)
}
