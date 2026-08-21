package com.docversion.storage;

import com.docversion.domain.FileContent;

/**
 * 파일 저장소 추상화. C++ FileStorage의 인터페이스화.
 * <p>이것이 Nextcloud(또는 S3/오브젝트 스토리지)가 꽂힐 핵심 seam.
 * 비즈니스 로직은 storageKey만 알고, 실제 물리 위치는 구현체가 결정한다
 * (versionId 문자열로 경로를 추론하지 않는다 — 05/18 ID 정책).
 * <p>현재는 LocalFileStorage(로컬 FS)로 채워두고, Nextcloud 확정 시 구현체만 교체.
 */
public interface StorageService {

    /** storageKey 위치에 콘텐츠 저장(디렉터리 자동 생성, 덮어쓰기). 실패 시 예외. */
    void writeFile(String storageKey, FileContent content);

    /**
     * storageKey 위치의 콘텐츠 읽기. 파일이 없으면 예외(빈 FileContent 반환 아님).
     * 빈 반환은 "파일 없음"과 "실제 빈 파일"을 구분 못 해 diff가 "전체 추가"로
     * 오해할 수 있으므로(C++ 주석 유지), 명확히 예외로 처리한다.
     */
    FileContent readFile(String storageKey);

    /** storageKey 위치의 파일 삭제. 없으면 조용히 무시, 그 외 오류는 예외. */
    void deleteFile(String storageKey);
}
