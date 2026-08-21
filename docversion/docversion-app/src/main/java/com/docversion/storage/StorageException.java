package com.docversion.storage;

/** 저장소 I/O 오류. 런타임 예외로 두어 @Transactional 롤백을 트리거. */
public class StorageException extends RuntimeException {
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
