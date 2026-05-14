package com.example.docver.model;

/**
 * 의사코드 ApprovalAction enum 이식.
 * 시연 단순화: CANCEL 제외 (시나리오 1에서 사용 안 함).
 * 향후 Java 전환 시 CANCEL 추가 가능.
 */
public enum ApprovalAction {
    REQUEST,    // 승인 요청
    APPROVE,    // 승인
    REJECT      // 거절
    // CANCEL — 시연 외 (의사코드 line 88-91 참조)
}
