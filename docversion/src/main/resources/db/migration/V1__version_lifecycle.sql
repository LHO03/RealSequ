-- ============================================================
-- V1: 버전 생명주기 슬라이스 스키마
-- 원본: Schema.sql (DocumentVersionWorkflowAPI MariaDB 스키마)에서
--       버전 생명주기가 실제로 건드리는 4개 테이블만 발췌.
--   documents       (RD-SRS-9.1)
--   files_versions  (RD-SRS-9.1, 9.2, 9.5, 9.10)
--   version_diffs   (RD-SRS-9.4)
--   activity        (RD-SRS-9.3)
-- 나머지 15개 테이블(승인/알림/보존 등)은 후속 슬라이스에서 추가.
-- 대상: MariaDB 10.11 LTS (JSON, JSON_SET 지원)
-- ============================================================

-- ------------------------------------------------------------
-- documents: 문서 master (RD-SRS-9.1)
--   file_id = UUID(CHAR(36)). 문서의 평생 식별자.
--   current_version_id ↔ files_versions.version_id 순환 참조 회피 위해 FK 미설정.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS documents (
    file_id             CHAR(36)        NOT NULL COMMENT '문서 고유 ID(UUID)',
    owner_user_id       VARCHAR(255)    NOT NULL COMMENT '문서 소유자',
    current_path        VARCHAR(1024)   NOT NULL COMMENT '사용자가 보는 현재 경로',
    original_name       VARCHAR(255)    NOT NULL COMMENT '최초 파일명',
    current_version_id  CHAR(36)        DEFAULT NULL COMMENT '현재 최신 버전 ID(UUID). FK 미설정',
    current_revision_no BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '현재 최신 리비전 번호',
    created_at          BIGINT          NOT NULL COMMENT '생성 시각(Unix epoch sec)',
    updated_at          BIGINT          NOT NULL COMMENT '수정 시각(Unix epoch sec)',
    deleted_at          BIGINT          DEFAULT NULL COMMENT '삭제 시각(soft delete)',

    PRIMARY KEY (file_id),
    -- current_path가 VARCHAR(1024)+utf8mb4(4byte/char)라 전체 인덱싱 시
    -- InnoDB 키 길이 한계(3072byte)를 초과 → 앞 255자만 prefix index.
    -- (원본 Schema.sql의 잠복 결함. 실제 MariaDB 적용 시 표면화되어 수정)
    INDEX idx_documents_owner_path (owner_user_id, current_path(255)),
    INDEX idx_documents_current_version (current_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='문서 master 테이블 (RD-SRS-9.1)';

-- ------------------------------------------------------------
-- files_versions: 파일 버전 관리 (RD-SRS-9.1, 9.2, 9.5, 9.10)
--   UNIQUE(file_id, revision_no)가 "고유 버전 번호"를 DB 차원에서 보장.
--   동시 수정 시 같은 revision_no 발급의 최종 방어선.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS files_versions (
    version_id   CHAR(36)        NOT NULL COMMENT '버전 고유 ID(UUID)',
    file_id      CHAR(36)        NOT NULL COMMENT 'documents.file_id 참조',
    revision_no  BIGINT UNSIGNED NOT NULL COMMENT '파일별 표시 버전 번호(1,2,3...)',
    user_id      VARCHAR(255)    NOT NULL COMMENT '버전 생성자',
    `timestamp`  BIGINT          NOT NULL COMMENT '생성 시각(Unix epoch sec)',
    size         BIGINT UNSIGNED NOT NULL COMMENT '파일 크기(bytes)',
    mimetype     VARCHAR(255)    NOT NULL COMMENT 'MIME 타입',
    storage_key  VARCHAR(512)    NOT NULL COMMENT '실제 저장 위치(objects/{fileId}/versions/{versionId})',
    metadata     JSON            DEFAULT NULL COMMENT '추가 메타데이터(author, reason, dlp.* 등)',

    PRIMARY KEY (version_id),
    UNIQUE INDEX uq_file_revision (file_id, revision_no),
    INDEX idx_file_timestamp (file_id, `timestamp` DESC),
    CONSTRAINT fk_versions_document
        FOREIGN KEY (file_id) REFERENCES documents(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='파일 버전 관리 (RD-SRS-9.1, 9.2, 9.5, 9.10)';

-- ------------------------------------------------------------
-- version_diffs: 버전 간 diff 결과 캐시 (RD-SRS-9.4)
--   onDocumentModified가 (이전→새) diff를 INSERT IGNORE로 적재.
--   UNIQUE(file_id, from_version_id, to_version_id)로 중복 캐시 차단.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS version_diffs (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'diff 캐시 고유 ID',
    file_id         VARCHAR(255)    NOT NULL COMMENT '대상 파일 ID(UUID)',
    from_version_id VARCHAR(64)     NOT NULL COMMENT '시작 버전 ID(UUID)',
    to_version_id   VARCHAR(64)     NOT NULL COMMENT '끝 버전 ID(UUID)',
    diff_method     VARCHAR(16)     NOT NULL COMMENT 'myers | myers_extracted | sha256 | binary',
    added_lines     INT             NOT NULL DEFAULT 0 COMMENT '추가 라인 수',
    deleted_lines   INT             NOT NULL DEFAULT 0 COMMENT '삭제 라인 수',
    summary         VARCHAR(512)    DEFAULT NULL COMMENT '요약 텍스트',
    hunks_json      LONGTEXT        DEFAULT NULL COMMENT 'Unified diff 전체 문자열/hunks',
    created_at      BIGINT          NOT NULL COMMENT '생성 시각',

    PRIMARY KEY (id),
    UNIQUE INDEX idx_version_pair (file_id, from_version_id, to_version_id),
    INDEX idx_file_created (file_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='버전 간 diff 결과 캐시 (RD-SRS-9.4)';

-- ------------------------------------------------------------
-- activity: 활동 로그 (RD-SRS-9.3) — Nextcloud activity 테이블 형태
--   logDocumentChangeHistory가 변경 이력을 기록.
--   `timestamp`, `user`는 예약어이므로 백틱 필요.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS activity (
    activity_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '활동 고유 ID',
    `timestamp`   BIGINT          NOT NULL COMMENT '활동 시각',
    `user`        VARCHAR(255)    NOT NULL COMMENT '수행자 ID',
    affecteduser  VARCHAR(255)    NOT NULL COMMENT '영향 받는 사용자',
    app           VARCHAR(64)     NOT NULL DEFAULT 'files' COMMENT '앱 이름',
    subject       VARCHAR(255)    NOT NULL COMMENT '활동 제목',
    subjectparams TEXT            DEFAULT NULL COMMENT '제목 파라미터(JSON)',
    file          VARCHAR(255)    DEFAULT NULL COMMENT '대상 파일 ID',
    object_type   VARCHAR(64)     NOT NULL COMMENT '객체 유형',
    object_id     VARCHAR(255)    NOT NULL COMMENT '객체 ID',

    PRIMARY KEY (activity_id),
    INDEX idx_user_timestamp (`user`, `timestamp`),
    INDEX idx_object (object_type, object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='활동 로그 (RD-SRS-9.3)';
