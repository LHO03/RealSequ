package com.docversion.event;

/**
 * 버전 생명주기 도메인 이벤트.
 * <p>C++ 주석의 "[전환 시] VersionCreatedEvent 발행 → @EventListener가 비동기 처리"를 구현.
 * 트랜잭션 커밋 이후(AFTER_COMMIT)에 알림/diff 캐시/보존 정책 같은 외부 부수효과를
 * 분리 실행하기 위한 seam. 부수효과 실패가 버전 생성 자체를 실패시키지 않는다.
 */
public final class VersionEvents {

    private VersionEvents() {
    }

    /** 최초 버전 생성 완료. */
    public record VersionCreated(String fileId, String versionId, long revisionNo, String userId) {
    }

    /**
     * 버전 수정 완료. diff 캐시 계산에 필요한 storage_key/versionId 페어를 함께 전달.
     * (새 콘텐츠는 storageKey로 재조회 — 이벤트에 바이트를 싣지 않음)
     */
    public record VersionUpdated(String fileId,
                                 String fromVersionId, String toVersionId,
                                 String fromStorageKey, String toStorageKey,
                                 String fromMimeType, String toMimeType,
                                 long previousRevisionNo, long newRevisionNo,
                                 String userId, long timestamp) {
    }
}
