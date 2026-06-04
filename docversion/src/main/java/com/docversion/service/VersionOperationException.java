package com.docversion.service;

/** 버전 생성/수정 실패. */
public class VersionOperationException extends RuntimeException {
    public VersionOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
