package com.docversion.service;

/**
 * 요청 자체가 잘못됨(필수 값 누락, 허용되지 않은 값, 범위 초과 등). 어드바이스에서 HTTP 400으로 변환.
 * <p>P1: 예) 승인자 0명, 알 수 없는 판정 방식, 음수 보관 기준, 알 수 없는 상태 문자열.
 * 과거엔 이들이 IllegalArgumentException→404로 잘못 나갔다.
 */
public class InvalidRequestException extends RuntimeException {
    public InvalidRequestException(String message) {
        super(message);
    }
}
