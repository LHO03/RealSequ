-- ============================================================
-- DocumentVersionWorkflowAPI MariaDB 스키마
-- 작성일: 2025-03-18
-- 대상: DocumentVersionWorkflowAPI.cpp에서 참조하는 전체 테이블
-- 요구사항: MariaDB 10.2.3+ (JSON_SET 지원)
-- ============================================================

-- 기존 테이블 삭제 (의존성 역순)
-- 주의: 개발/테스트 환경 전용. 운영 환경에서는 절대 사용 금지
DROP TABLE IF EXISTS notifications_mq;
DROP TABLE IF EXISTS notifications_pushhash;
DROP TABLE IF EXISTS notifications;
DROP TABLE IF EXISTS approval_activity;
DROP TABLE IF EXISTS approval_rule_approvers;
DROP TABLE IF EXISTS approval_rule_requesters;
DROP TABLE IF EXISTS approval_rules;
DROP TABLE IF EXISTS systemtag_object_mapping;
DROP TABLE IF EXISTS systemtag;
DROP TABLE IF EXISTS activity;
DROP TABLE IF EXISTS files_versions;

-- ============================================================
-- 1. files_versions: 파일 버전 관리 (RD-SRS-9.1, 9.2, 9.5, 9.10)
-- ============================================================
-- 코드 참조:
--   createInitialVersion  → INSERT (version_id, file_id, user_id, `timestamp`, size, mimetype, metadata)
--   onDocumentModified    → INSERT (동일)
--   logDocumentChangeHistory → UPDATE metadata (JSON_SET)
--   getVersionsAtTime     → SELECT (file_id, user_id, `timestamp`, size, mimetype, metadata)
--   applyVersionRetentionPolicy → SELECT (version_id, file_id, `timestamp`, size), DELETE (version_id)
-- 03/18 - version_id 추가: 초단위 timestamp 충돌 방지 (timestamp + counter)
CREATE TABLE files_versions (
    version_id  VARCHAR(512)    NOT NULL    COMMENT '버전 고유 ID (fileId.v{timestamp}_{counter})',
    file_id     VARCHAR(255)    NOT NULL    COMMENT '파일 고유 ID',
    user_id     VARCHAR(255)    NOT NULL    COMMENT '버전 생성자 ID',
    `timestamp` BIGINT          NOT NULL    COMMENT '버전 생성 시각 (Unix timestamp, 초 단위)',
    size        BIGINT UNSIGNED NOT NULL    COMMENT '파일 크기 (bytes)',
    mimetype    VARCHAR(255)    NOT NULL    COMMENT 'MIME 타입 (예: application/pdf)',
    metadata    JSON            DEFAULT NULL COMMENT '추가 메타데이터 (author, reason, DLP 필드 등)',

    PRIMARY KEY (version_id),
    INDEX idx_file_timestamp (file_id, `timestamp` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='파일 버전 스냅샷 (RD-SRS-9.1, 9.2)';

-- ============================================================
-- 2. activity: 활동 로그 (RD-SRS-9.3)
-- ============================================================
-- 코드 참조:
--   logDocumentChangeHistory → INSERT (`timestamp`, `user`, affecteduser, app,
--                                      subject, subjectparams, file, object_type, object_id)
CREATE TABLE activity (
    activity_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '활동 고유 ID',
    `timestamp` BIGINT          NOT NULL    COMMENT '활동 시각 (Unix timestamp)',
    `user`      VARCHAR(255)    NOT NULL    COMMENT '활동 수행자 ID',
    affecteduser VARCHAR(255)   NOT NULL    COMMENT '영향 받는 사용자 ID',
    app         VARCHAR(64)     NOT NULL    DEFAULT 'files' COMMENT '앱 이름',
    subject     VARCHAR(255)    NOT NULL    COMMENT '활동 제목 (예: file_status_changed)',
    subjectparams TEXT          DEFAULT NULL COMMENT '제목 파라미터 (JSON)',
    file        VARCHAR(255)    DEFAULT NULL COMMENT '대상 파일 ID',
    object_type VARCHAR(64)     NOT NULL    COMMENT '객체 유형 (예: files)',
    object_id   VARCHAR(255)    NOT NULL    COMMENT '객체 ID',

    PRIMARY KEY (activity_id),
    INDEX idx_user_timestamp (`user`, `timestamp`),
    INDEX idx_object (object_type, object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='활동 로그 기록 (RD-SRS-9.3)';

-- ============================================================
-- 3. systemtag: 시스템 태그 정의 (RD-SRS-9.6)
-- ============================================================
-- 코드 참조:
--   setDocumentStatus → SELECT id WHERE name = ?, INSERT (id, name, visibility, editable)
-- 문서 상태를 태그로 관리: draft, under_review, approved, rejected, deprecated
CREATE TABLE systemtag (
    id          VARCHAR(255)    NOT NULL    COMMENT '태그 고유 ID (UUID)',
    name        VARCHAR(255)    NOT NULL    COMMENT '태그 이름 (예: draft, approved)',
    visibility  TINYINT         NOT NULL    DEFAULT 1 COMMENT '가시성 (1=보임)',
    editable    TINYINT         NOT NULL    DEFAULT 1 COMMENT '편집 가능 여부 (1=가능)',

    PRIMARY KEY (id),
    UNIQUE INDEX idx_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='시스템 태그 정의 (RD-SRS-9.6)';

-- ============================================================
-- 4. systemtag_object_mapping: 태그-파일 매핑 (RD-SRS-9.6)
-- ============================================================
-- 코드 참조:
--   setDocumentStatus → DELETE (기존 태그 제거), REPLACE INTO (새 태그 할당)
--   getCurrentStatusTag → SELECT (JOIN systemtag)
--   processApprovalWorkflow → SELECT (중복 승인 요청 체크)
-- REPLACE INTO가 동작하려면 UNIQUE 제약 조건이 필요
CREATE TABLE systemtag_object_mapping (
    objectid    VARCHAR(255)    NOT NULL    COMMENT '대상 객체 ID (파일 ID)',
    objecttype  VARCHAR(64)     NOT NULL    DEFAULT 'files' COMMENT '객체 유형',
    systemtagid VARCHAR(255)    NOT NULL    COMMENT '태그 ID (systemtag.id 참조)',

    PRIMARY KEY (objectid, objecttype, systemtagid),
    UNIQUE INDEX idx_object_type (objectid, objecttype),
    INDEX idx_tagid (systemtagid),
    CONSTRAINT fk_mapping_tag FOREIGN KEY (systemtagid) REFERENCES systemtag(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='태그-객체 매핑. 한 파일은 하나의 상태 태그만 가짐 (RD-SRS-9.6)';

-- ============================================================
-- 5. approval_rules: 승인 규칙 (RD-SRS-9.7)
-- ============================================================
-- 코드 참조:
--   processApprovalWorkflow REQUEST → INSERT (id, file_id, tag_pending, tag_approved, tag_rejected, status)
--   processApprovalDecision → SELECT id WHERE tag_pending = ? AND file_id = ? AND status = 'OPEN'
--   processApprovalDecision → UPDATE status = 'CLOSED' WHERE id = ?
-- 03/18 - status 컬럼 추가: stale rule 방지 (OPEN → CLOSED 생명주기)
CREATE TABLE approval_rules (
    id              VARCHAR(255)    NOT NULL    COMMENT '규칙 고유 ID (UUID)',
    file_id         VARCHAR(255)    NOT NULL    COMMENT '대상 파일 ID',
    tag_pending     VARCHAR(255)    NOT NULL    COMMENT '대기 상태 태그 (under_review)',
    tag_approved    VARCHAR(255)    NOT NULL    COMMENT '승인 상태 태그 (approved)',
    tag_rejected    VARCHAR(255)    NOT NULL    COMMENT '거절 상태 태그 (rejected)',
    status          VARCHAR(16)     NOT NULL    DEFAULT 'OPEN' COMMENT '규칙 상태: OPEN(진행 중) / CLOSED(완료)',

    PRIMARY KEY (id),
    INDEX idx_file_pending_status (file_id, tag_pending, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인 워크플로우 규칙 (RD-SRS-9.7)';

-- ============================================================
-- 6. approval_rule_requesters: 승인 요청자 (RD-SRS-9.7)
-- ============================================================
-- 코드 참조:
--   processApprovalWorkflow REQUEST → INSERT (rule_id, entity_type, entity_id)
--   processApprovalDecision → SELECT entity_id WHERE rule_id = ?
CREATE TABLE approval_rule_requesters (
    rule_id     VARCHAR(255)    NOT NULL    COMMENT '규칙 ID (approval_rules.id 참조)',
    entity_type VARCHAR(64)     NOT NULL    DEFAULT 'user' COMMENT '엔티티 유형',
    entity_id   VARCHAR(255)    NOT NULL    COMMENT '요청자 ID',

    PRIMARY KEY (rule_id, entity_type, entity_id),
    CONSTRAINT fk_requester_rule FOREIGN KEY (rule_id) REFERENCES approval_rules(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인 요청자 등록 (RD-SRS-9.7)';

-- ============================================================
-- 7. approval_rule_approvers: 승인자 (RD-SRS-9.7)
-- ============================================================
-- 코드 참조:
--   processApprovalWorkflow REQUEST → INSERT (rule_id, entity_type, entity_id)
--   processApprovalDecision → SELECT rule_id WHERE entity_id = ? AND rule_id IN (...)
CREATE TABLE approval_rule_approvers (
    rule_id     VARCHAR(255)    NOT NULL    COMMENT '규칙 ID (approval_rules.id 참조)',
    entity_type VARCHAR(64)     NOT NULL    DEFAULT 'user' COMMENT '엔티티 유형',
    entity_id   VARCHAR(255)    NOT NULL    COMMENT '승인자 ID',

    PRIMARY KEY (rule_id, entity_type, entity_id),
    INDEX idx_entity (entity_id),
    CONSTRAINT fk_approver_rule FOREIGN KEY (rule_id) REFERENCES approval_rules(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인자 등록 (RD-SRS-9.7)';

-- ============================================================
-- 8. approval_activity: 승인/거절 이력 (RD-SRS-9.7)
-- ============================================================
-- 코드 참조:
--   processApprovalDecision → INSERT (rule_id, user_id, action, `timestamp`, comment)
CREATE TABLE approval_activity (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '이력 고유 ID',
    rule_id     VARCHAR(255)    NOT NULL    COMMENT '규칙 ID (approval_rules.id 참조)',
    user_id     VARCHAR(255)    NOT NULL    COMMENT '승인/거절 수행자 ID',
    action      VARCHAR(64)     NOT NULL    COMMENT '액션 태그 (approved / rejected)',
    `timestamp` BIGINT          NOT NULL    COMMENT '수행 시각 (Unix timestamp)',
    comment     TEXT            DEFAULT NULL COMMENT '승인/거절 사유',

    PRIMARY KEY (id),
    INDEX idx_rule (rule_id),
    INDEX idx_user_action (user_id, action),
    CONSTRAINT fk_activity_rule FOREIGN KEY (rule_id) REFERENCES approval_rules(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인/거절 이력 기록 (RD-SRS-9.7)';

-- ============================================================
-- 9. notifications: 알림 저장 (RD-SRS-9.9)
-- ============================================================
-- 코드 참조:
--   notifyStakeholders → INSERT (notification_id, app, `user`, `timestamp`,
--                                object_type, object_id, subject, message)
CREATE TABLE notifications (
    notification_id VARCHAR(255)    NOT NULL    COMMENT '알림 고유 ID (UUID)',
    app             VARCHAR(64)     NOT NULL    DEFAULT 'files' COMMENT '앱 이름',
    `user`          VARCHAR(255)    NOT NULL    COMMENT '수신자 ID',
    `timestamp`     BIGINT          NOT NULL    COMMENT '알림 시각 (Unix timestamp)',
    object_type     VARCHAR(64)     NOT NULL    COMMENT '객체 유형',
    object_id       VARCHAR(255)    NOT NULL    COMMENT '객체 ID',
    subject         VARCHAR(255)    NOT NULL    COMMENT '알림 제목 (이벤트 유형)',
    message         TEXT            DEFAULT NULL COMMENT '알림 본문',

    PRIMARY KEY (notification_id),
    INDEX idx_user_timestamp (`user`, `timestamp`),
    INDEX idx_object (object_type, object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='알림 저장소 (RD-SRS-9.9)';

-- ============================================================
-- 10. notifications_pushhash: 푸시 알림 토큰 (RD-SRS-9.9)
-- ============================================================
-- 코드 참조:
--   notifyStakeholders → SELECT token WHERE uid = ?
CREATE TABLE notifications_pushhash (
    id      BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '토큰 고유 ID',
    uid     VARCHAR(255)    NOT NULL    COMMENT '사용자 ID',
    token   VARCHAR(512)    NOT NULL    COMMENT '푸시 토큰 (FCM/APNS)',

    PRIMARY KEY (id),
    INDEX idx_uid (uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='푸시 알림 디바이스 토큰 (RD-SRS-9.9)';

-- ============================================================
-- 11. notifications_mq: 이메일 알림 큐 (RD-SRS-9.9)
-- ============================================================
-- 코드 참조:
--   notifyStakeholders → INSERT (amq_timestamp, amq_affecteduser,
--                                amq_appid, amq_subject, amq_subjectparams)
CREATE TABLE notifications_mq (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '큐 고유 ID',
    amq_timestamp       BIGINT          NOT NULL    COMMENT '큐 등록 시각 (Unix timestamp)',
    amq_affecteduser    VARCHAR(255)    NOT NULL    COMMENT '수신 대상 사용자 ID',
    amq_appid           VARCHAR(64)     NOT NULL    DEFAULT 'files' COMMENT '앱 ID',
    amq_subject         VARCHAR(255)    NOT NULL    COMMENT '이메일 제목 (이벤트 유형)',
    amq_subjectparams   TEXT            DEFAULT NULL COMMENT '제목 파라미터',

    PRIMARY KEY (id),
    INDEX idx_user_timestamp (amq_affecteduser, amq_timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='이메일 알림 발송 큐 (RD-SRS-9.9)';

-- ============================================================
-- 초기 데이터: 문서 상태 태그 미리 생성
-- ============================================================
-- setDocumentStatus에서 태그가 없으면 동적으로 생성하지만,
-- 미리 만들어두면 첫 호출 시 INSERT를 건너뛸 수 있어 효율적
INSERT INTO systemtag (id, name, visibility, editable) VALUES
    ('tag_draft',        'draft',        1, 1),
    ('tag_under_review', 'under_review', 1, 1),
    ('tag_approved',     'approved',     1, 1),
    ('tag_rejected',     'rejected',     1, 1),
    ('tag_deprecated',   'deprecated',   1, 1);