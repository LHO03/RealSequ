package com.example.docver.model;

/**
 * 의사코드 ApprovalAction enum 이식.
 * 05/15: CANCEL 추가 (시나리오 4: 승인 요청 취소).
 */
public enum ApprovalAction {
    REQUEST,    // 승인 요청
    APPROVE,    // 승인
    REJECT,     // 거절
    CANCEL      // 승인 요청 취소 (요청자 본인 또는 승인자 또는 ADMIN)
}
