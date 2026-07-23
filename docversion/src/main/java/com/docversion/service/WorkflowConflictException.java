package com.docversion.service;

/**
 * 현재 상태와 충돌하는 요청(이미 열린 승인 요청, 허용되지 않는 상태 전이, 이미 판정됨,
 * 지금 차례가 아님 등). 어드바이스에서 HTTP 409(CONFLICT)로 변환.
 * <p>내부 정합성 이상(라이브 포인터 없음 등)은 이 타입이 아니라 IllegalStateException으로 남겨
 * 500으로 떨어지게 한다 — 사용자 입력·상태 문제가 아니라 서버 데이터 이상이기 때문.
 */
public class WorkflowConflictException extends RuntimeException {
    public WorkflowConflictException(String message) {
        super(message);
    }
}
