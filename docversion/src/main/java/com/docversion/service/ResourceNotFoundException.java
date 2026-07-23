package com.docversion.service;

/**
 * 요청한 자원이 존재하지 않음(문서/버전/정책 등). 컨트롤러 어드바이스에서 HTTP 404로 변환.
 * <p>P1: 기존에는 not-found와 bad-input이 모두 IllegalArgumentException이라 컨트롤러가 전부 404로
 * 매핑했다. 이제 "없음"은 이 타입으로 분리해 404, "잘못된 입력"은 InvalidRequestException으로 400.
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
