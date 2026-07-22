package com.docversion.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashMap;
import java.util.Map;

/**
 * 버전 정보. C++ {@code struct VersionInfo}의 직역이자 files_versions 행 매핑 대상.
 * <p>05/18 ID 정책 개정 반영: versionId/fileId 모두 UUID, revisionNo는 표시용 번호,
 * storageKey는 실제 저장 위치(versionId 문자열로 경로 추론 금지).
 * <p>MyBatis가 snake_case 컬럼을 camelCase 필드로 매핑(map-underscore-to-camel-case).
 * metadata(JSON 문자열)는 별도 컬럼으로 받고, 파싱된 형태는 metadataMap에 둔다.
 */
public class VersionInfo {

    private String versionId;
    private String fileId;
    private long revisionNo;
    private String userId;
    private long timestamp;
    private long size;
    private String mimetype;
    private String storageKey;

    /** files_versions.metadata 컬럼 원본(JSON 문자열). */
    private String metadata;

    /** 파싱된 메타데이터(author, reason 등). DB 컬럼 아님 — 서비스에서 채움. */
    private Map<String, String> metadataMap = new HashMap<>();

    public VersionInfo() {
    }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public long getRevisionNo() { return revisionNo; }
    public void setRevisionNo(long revisionNo) { this.revisionNo = revisionNo; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getMimetype() { return mimetype; }
    public void setMimetype(String mimetype) { this.mimetype = mimetype; }

    /** 07/19 - P1-②: 내부 저장 경로는 API 응답(JSON)에서 숨긴다 — 서버 내부 구조 노출 방지. */
    @JsonIgnore
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    // 07/19 - P1-②: 원본 metadata JSON 문자열은 응답에서 제외한다(외부 리뷰 지적).
    //   내부 로직과 DB 매핑은 필드에 직접 접근하므로 영향 없고, 표시용 값은
    //   파싱된 metadataMap(author·reason 등)으로만 내려간다.
    @JsonIgnore
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public Map<String, String> getMetadataMap() { return metadataMap; }
    public void setMetadataMap(Map<String, String> metadataMap) { this.metadataMap = metadataMap; }

    /** 빈 결과 표현. C++의 {@code return VersionInfo{};}에 대응. */
    public boolean isEmpty() {
        return versionId == null || versionId.isBlank();
    }
}
