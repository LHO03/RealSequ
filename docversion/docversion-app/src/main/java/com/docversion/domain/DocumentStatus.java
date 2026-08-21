package com.docversion.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 문서 상태와 허용 전이 (RD-SRS-9.6).
 * 명세서 상태 전이 다이어그램을 그대로 옮긴 것이다.
 *
 *   초안(DRAFT) ──제출──▶ 검토중(UNDER_REVIEW)
 *   검토중 ──수정 요청──▶ 초안
 *   검토중 ──승인──▶ 승인(APPROVED)
 *   승인 ──수정 시작──▶ 수정본_초안(REVISION_DRAFT)
 *   수정본_초안 ──제출──▶ 검토중
 *   승인 ──폐기 결정──▶ 폐기(DEPRECATED)
 *   폐기 ──복원──▶ 초안
 */
public enum DocumentStatus {
    DRAFT("초안"),
    UNDER_REVIEW("검토중"),
    APPROVED("승인"),
    REVISION_DRAFT("수정본 초안"),
    DEPRECATED("폐기");

    private final String label;

    DocumentStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    // 상태별 허용되는 다음 상태 집합
    private static final Map<DocumentStatus, Set<DocumentStatus>> TRANSITIONS =
            new EnumMap<>(DocumentStatus.class);

    static {
        TRANSITIONS.put(DRAFT, EnumSet.of(UNDER_REVIEW));
        TRANSITIONS.put(UNDER_REVIEW, EnumSet.of(DRAFT, APPROVED));
        TRANSITIONS.put(APPROVED, EnumSet.of(REVISION_DRAFT, DEPRECATED));
        TRANSITIONS.put(REVISION_DRAFT, EnumSet.of(UNDER_REVIEW));
        TRANSITIONS.put(DEPRECATED, EnumSet.of(DRAFT));
    }

    /** 이 상태에서 전이 가능한 다음 상태들. */
    public Set<DocumentStatus> allowedTargets() {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(DocumentStatus.class));
    }

    /** target 상태로 전이가 허용되는지. */
    public boolean canTransitionTo(DocumentStatus target) {
        return allowedTargets().contains(target);
    }

    /** 문자열 → enum. 알 수 없는 값은 명확한 예외. */
    public static DocumentStatus of(String s) {
        if (s == null) {
            throw new IllegalArgumentException("상태 값이 비어 있습니다.");
        }
        try {
            return valueOf(s.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("알 수 없는 문서 상태: " + s);
        }
    }
}
