package com.docversion.service;

/**
 * 인가(권한) 위반. 인증 3단계: 신원은 확인됐지만 그 자원에 대한 권한이 없는 경우.
 * <p>컨트롤러에서 HTTP 403(FORBIDDEN)으로 변환한다.
 * 401(인증 안 됨)과 구분: 401은 "누군지 모름", 403은 "누군지 알지만 자격 없음".
 */
public class ForbiddenOperationException extends RuntimeException {
    public ForbiddenOperationException(String message) {
        super(message);
    }
}
