package com.example.docver.model;

/**
 * 의사코드 DocumentStatus enum 이식.
 * 태그 이름과의 매핑은 setDocumentStatus에서 처리.
 */
public enum DocumentStatus {
    DRAFT,          // 초안
    UNDER_REVIEW,   // 검토중
    APPROVED,       // 승인됨
    REJECTED,       // 거절됨
    DEPRECATED;     // 폐기됨

    /**
     * DB 태그명으로 변환 (의사코드의 TAG_* 상수 매핑).
     */
    public String toTagName() {
        switch (this) {
            case DRAFT:        return "draft";
            case UNDER_REVIEW: return "under_review";
            case APPROVED:     return "approved";
            case REJECTED:     return "rejected";
            case DEPRECATED:   return "deprecated";
            default:
                throw new IllegalArgumentException("Unknown status: " + this);
        }
    }
}
