-- ============================================================
-- 알파 시연용 스키마 (시나리오 1)
-- 의사코드 Schema.sql 509줄 중 9개 테이블만 추출
-- 시연 외 테이블 (보존정책, 위임, 권한, 구독, Outbox 등) 제외
-- ============================================================

-- 의존성 역순 DROP
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS approval_activity;
DROP TABLE IF EXISTS approval_rule_approvers;
DROP TABLE IF EXISTS approval_rule_requesters;
DROP TABLE IF EXISTS approval_rules;
DROP TABLE IF EXISTS systemtag_object_mapping;
DROP TABLE IF EXISTS systemtag;
DROP TABLE IF EXISTS activity;
DROP TABLE IF EXISTS files_versions;

-- 1. files_versions: 버전 스냅샷 (RD-SRS-9.1, 9.2, 9.5)
CREATE TABLE files_versions (
    version_id  VARCHAR(512)    NOT NULL,
    file_id     VARCHAR(255)    NOT NULL,
    user_id     VARCHAR(255)    NOT NULL,
    `timestamp` BIGINT          NOT NULL,
    size        BIGINT UNSIGNED NOT NULL,
    mimetype    VARCHAR(255)    NOT NULL,
    metadata    JSON            DEFAULT NULL,
    PRIMARY KEY (version_id),
    INDEX idx_file_timestamp (file_id, `timestamp` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. activity: 활동 로그 (RD-SRS-9.3)
CREATE TABLE activity (
    activity_id   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `timestamp`   BIGINT          NOT NULL,
    `user`        VARCHAR(255)    NOT NULL,
    affecteduser  VARCHAR(255)    NOT NULL,
    app           VARCHAR(64)     NOT NULL DEFAULT 'files',
    subject       VARCHAR(255)    NOT NULL,
    subjectparams TEXT            DEFAULT NULL,
    file          VARCHAR(255)    DEFAULT NULL,
    object_type   VARCHAR(64)     NOT NULL,
    object_id     VARCHAR(255)    NOT NULL,
    PRIMARY KEY (activity_id),
    INDEX idx_user_timestamp (`user`, `timestamp`),
    INDEX idx_object (object_type, object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. systemtag: 상태 태그 정의 (RD-SRS-9.6)
CREATE TABLE systemtag (
    id         VARCHAR(255)    NOT NULL,
    name       VARCHAR(255)    NOT NULL,
    visibility TINYINT         NOT NULL DEFAULT 1,
    editable   TINYINT         NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. systemtag_object_mapping: 태그-파일 매핑 (RD-SRS-9.6)
-- UNIQUE INDEX로 한 파일 한 status 보장 (의사코드와 동일)
CREATE TABLE systemtag_object_mapping (
    objectid    VARCHAR(255) NOT NULL,
    objecttype  VARCHAR(64)  NOT NULL DEFAULT 'files',
    systemtagid VARCHAR(255) NOT NULL,
    PRIMARY KEY (objectid, objecttype, systemtagid),
    UNIQUE INDEX idx_object_type (objectid, objecttype),
    CONSTRAINT fk_mapping_tag FOREIGN KEY (systemtagid) REFERENCES systemtag(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. approval_rules: 승인 규칙 (RD-SRS-9.7)
-- 시연 단순화: consensus_mode = 'THRESHOLD' 고정, required_approvals = 1
CREATE TABLE approval_rules (
    id                  VARCHAR(255) NOT NULL,
    file_id             VARCHAR(255) NOT NULL,
    tag_pending         VARCHAR(255) NOT NULL,
    tag_approved        VARCHAR(255) NOT NULL,
    tag_rejected        VARCHAR(255) NOT NULL,
    status              VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    consensus_mode      VARCHAR(16)  NOT NULL DEFAULT 'THRESHOLD',
    required_approvals  INT          NOT NULL DEFAULT 1,
    received_approvals  INT          NOT NULL DEFAULT 0,
    received_rejections INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_file_pending_status (file_id, tag_pending, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. approval_rule_requesters
CREATE TABLE approval_rule_requesters (
    rule_id     VARCHAR(255) NOT NULL,
    entity_type VARCHAR(64)  NOT NULL DEFAULT 'user',
    entity_id   VARCHAR(255) NOT NULL,
    PRIMARY KEY (rule_id, entity_type, entity_id),
    CONSTRAINT fk_requester_rule FOREIGN KEY (rule_id) REFERENCES approval_rules(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. approval_rule_approvers
CREATE TABLE approval_rule_approvers (
    rule_id        VARCHAR(255) NOT NULL,
    entity_type    VARCHAR(64)  NOT NULL DEFAULT 'user',
    entity_id      VARCHAR(255) NOT NULL,
    sequence_order INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (rule_id, entity_type, entity_id),
    INDEX idx_entity (entity_id),
    CONSTRAINT fk_approver_rule FOREIGN KEY (rule_id) REFERENCES approval_rules(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. approval_activity: 승인/거절 이력
CREATE TABLE approval_activity (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    rule_id     VARCHAR(255)    NOT NULL,
    user_id     VARCHAR(255)    NOT NULL,
    action      VARCHAR(32)     NOT NULL,
    `timestamp` BIGINT          NOT NULL,
    comment     TEXT            DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_rule (rule_id),
    CONSTRAINT fk_activity_rule FOREIGN KEY (rule_id) REFERENCES approval_rules(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. notifications: 알림 (RD-SRS-9.9)
-- 시연 단순화: dedup_key 제거 (단일 사용자 시연이라 중복 없음)
CREATE TABLE notifications (
    notification_id VARCHAR(255)    NOT NULL,
    app             VARCHAR(64)     NOT NULL DEFAULT 'files',
    `user`          VARCHAR(255)    NOT NULL,
    `timestamp`     BIGINT          NOT NULL,
    object_type     VARCHAR(64)     NOT NULL,
    object_id       VARCHAR(255)    NOT NULL,
    subject         VARCHAR(255)    NOT NULL,
    message         TEXT            DEFAULT NULL,
    read_at         BIGINT          DEFAULT NULL,
    PRIMARY KEY (notification_id),
    INDEX idx_user_timestamp (`user`, `timestamp` DESC),
    INDEX idx_user_unread (`user`, read_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
