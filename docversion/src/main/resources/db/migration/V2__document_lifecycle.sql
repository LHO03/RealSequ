-- ============================================================
-- V2: 문서 상태 관리 (RD-SRS-9.6) + 상태 변경 이력
--   - documents에 status 컬럼 추가 (기본값 DRAFT → 기존 생성 경로 무수정)
--   - document_status_history: 누가/언제/무엇을→무엇으로/왜 바꿨는지 기록
--     (RD-SRS-9.3의 "변경 이력" 정신을 상태 변경에도 적용, 사유 포함)
--   상태 전이 규칙은 애플리케이션(DocumentStatus)에서 강제한다.
-- 대상: MariaDB 10.11 LTS
-- ============================================================

ALTER TABLE documents
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
        COMMENT '문서 상태(DRAFT/UNDER_REVIEW/APPROVED/REVISION_DRAFT/DEPRECATED)',
    ADD COLUMN status_updated_at BIGINT DEFAULT NULL
        COMMENT '상태 변경 시각(Unix epoch sec)';

CREATE TABLE IF NOT EXISTS document_status_history (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '이력 고유 ID',
    file_id      CHAR(36)        NOT NULL COMMENT 'documents.file_id 참조',
    from_status  VARCHAR(32)     DEFAULT NULL COMMENT '이전 상태(없으면 NULL)',
    to_status    VARCHAR(32)     NOT NULL COMMENT '변경 후 상태',
    changed_by   VARCHAR(255)    NOT NULL COMMENT '변경자',
    reason       VARCHAR(1024)   DEFAULT NULL COMMENT '변경 사유',
    changed_at   BIGINT          NOT NULL COMMENT '변경 시각(Unix epoch sec)',

    PRIMARY KEY (id),
    INDEX idx_status_hist_file (file_id, changed_at),
    CONSTRAINT fk_status_hist_document
        FOREIGN KEY (file_id) REFERENCES documents(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='문서 상태 변경 이력 (RD-SRS-9.6, 9.3 정신 적용)';
