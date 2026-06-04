-- ============================================================
-- DocumentVersionWorkflowAPI MariaDB 스키마
-- 작성일: 2025-03-18
-- 대상: DocumentVersionWorkflowAPI.cpp에서 참조하는 전체 테이블
-- 요구사항: MariaDB 10.2.3+ (JSON_SET 지원)
-- ============================================================

-- 기존 테이블 삭제 (의존성 역순)
-- 주의: 개발/테스트 환경 전용. 운영 환경에서는 절대 사용 금지
DROP TABLE IF EXISTS retention_policies;
DROP TABLE IF EXISTS approval_delegations;
DROP TABLE IF EXISTS user_roles;
DROP TABLE IF EXISTS notification_outbox;
DROP TABLE IF EXISTS file_subscription_channels;
DROP TABLE IF EXISTS file_subscriptions;
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
DROP TABLE IF EXISTS version_diffs;
DROP TABLE IF EXISTS files_versions;
DROP TABLE IF EXISTS documents;

-- ============================================================
-- 0. documents: 문서 master 테이블 (RD-SRS-9.1)
-- ============================================================
-- 05/18 - ID 정책 개정 반영:
--   - file_id를 UUID(CHAR(36))로 사용하여 문서의 평생 식별자 역할.
--   - current_path는 사용자 표시용. 이동/이름변경 시 이 컬럼만 UPDATE.
--   - current_version_id / current_revision_no는 "현재 최신 버전" 포인터.
--     onDocumentModified가 새 버전 생성 시 두 컬럼을 함께 UPDATE.
--   - current_version_id ↔ files_versions.version_id 의 순환 참조 회피를 위해
--     이 컬럼에는 FK를 의도적으로 걸지 않는다 (DELETE 순서 종속성 회피).
-- 코드 참조:
--   createInitialVersion → INSERT (file_id, owner_user_id, current_path, original_name,
--                                  current_version_id, current_revision_no, created_at, updated_at)
--   onDocumentModified   → SELECT (current_version_id, current_revision_no),
--                          UPDATE (current_version_id, current_revision_no, updated_at)
CREATE TABLE IF NOT EXISTS documents (
    file_id             CHAR(36)      NOT NULL COMMENT '문서 고유 ID(UUID)',
    owner_user_id       VARCHAR(255)  NOT NULL COMMENT '문서 소유자',
    current_path        VARCHAR(1024) NOT NULL COMMENT '사용자가 보는 현재 문서 경로 (이동/이름변경 시 UPDATE)',
    original_name       VARCHAR(255)  NOT NULL COMMENT '최초 파일명',
    current_version_id  CHAR(36)      DEFAULT NULL COMMENT '현재 최신 버전 ID(UUID). FK 미설정(순환 참조 회피)',
    current_revision_no BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '현재 최신 리비전 번호 (사용자 표시용)',
    created_at          BIGINT        NOT NULL COMMENT '생성 시각',
    updated_at          BIGINT        NOT NULL COMMENT '수정 시각',
    deleted_at          BIGINT        DEFAULT NULL COMMENT '삭제 시각 (soft delete)',

    PRIMARY KEY (file_id),
    INDEX idx_documents_owner_path (owner_user_id, current_path),
    INDEX idx_documents_current_version (current_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='문서 master 테이블 (RD-SRS-9.1)';

-- ============================================================
-- 1. files_versions: 파일 버전 관리 (RD-SRS-9.1, 9.2, 9.5, 9.10)
-- ============================================================
-- 코드 참조 (05/18 ID 정책 개정 후):
--   createInitialVersion  → INSERT (version_id, file_id, revision_no, user_id,
--                                   `timestamp`, size, mimetype, storage_key, metadata)
--   onDocumentModified    → INSERT (동일 컬럼셋)
--                          SELECT storage_key (이전 버전 콘텐츠 읽기 위해)
--   logDocumentChangeHistory → UPDATE metadata (JSON_SET)
--   getVersionsAtTime     → SELECT (version_id, file_id, revision_no, user_id,
--                                   `timestamp`, size, mimetype, storage_key, metadata)
--   prepareVersionComparison → SELECT storage_key (콘텐츠 읽기 위해)
--   deleteVersion         → SELECT (file_id, `timestamp`, user_id, storage_key),
--                          DELETE (version_id)
--   applyVersionRetentionPolicy → SELECT (version_id, file_id, `timestamp`,
--                                         size, storage_key),
--                                 DELETE (version_id)
--
-- 05/18 - ID 정책:
--   version_id    : UUID. 내부 식별자. 외부 노출/FK용. 의미 없음(=보안상 좋은 성질)
--   file_id       : UUID. documents.file_id 참조
--   revision_no   : 사용자에게 보이는 버전 번호 (1, 2, 3 ...).
--                   UNIQUE(file_id, revision_no)로 파일별 중복 방지.
--                   → 9.1 "고유 버전 번호"의 DB 차원 보장.
--   storage_key   : 실제 버전 파일 저장 위치. 코드는 이 컬럼으로만 파일을 읽고 쓴다.
--                   versionId 문자열로 경로를 추론해서는 안 됨.
-- 03/18 - version_id 추가: 초단위 timestamp 충돌 방지 (legacy 비고)
CREATE TABLE files_versions (
    version_id   CHAR(36)      NOT NULL COMMENT '버전 고유 ID(UUID). 내부 식별자',
    file_id      CHAR(36)      NOT NULL COMMENT '문서 고유 ID(UUID). documents.file_id 참조',
    revision_no  BIGINT UNSIGNED NOT NULL COMMENT '파일별 사용자 표시 버전 번호 (1, 2, 3 ...)',
    user_id      VARCHAR(255)  NOT NULL COMMENT '버전 생성자 ID',
    `timestamp`  BIGINT        NOT NULL COMMENT '버전 생성 시각 (Unix epoch, 초 단위)',
    size         BIGINT UNSIGNED NOT NULL COMMENT '파일 크기(bytes)',
    mimetype     VARCHAR(255)  NOT NULL COMMENT 'MIME 타입',
    storage_key  VARCHAR(512)  NOT NULL COMMENT '실제 버전 파일 저장 위치 (예: objects/{fileId}/versions/{versionId})',
    metadata     JSON          DEFAULT NULL COMMENT '추가 메타데이터 (author, dlp.*, 등)',

    PRIMARY KEY (version_id),
    UNIQUE INDEX uq_file_revision (file_id, revision_no),
    INDEX idx_file_timestamp (file_id, `timestamp` DESC),
    CONSTRAINT fk_versions_document
        FOREIGN KEY (file_id) REFERENCES documents(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='파일 버전 관리 (RD-SRS-9.1, 9.2, 9.5, 9.10)';

-- ============================================================
-- 1-2. version_diffs: 버전 간 diff 결과 캐시 (RD-SRS-9.4)
-- ============================================================
-- 04/30 - Phase A-5: diff 결과 영속화
--   목적: prepareVersionComparison 호출 시 매번 재계산하던 것을 캐시하여 응답 속도 향상
--   동작: onDocumentModified가 새 버전 생성 시 자동으로 (이전→새) diff를 INSERT.
--         prepareVersionComparison은 캐시 우선 조회, 없으면 계산 후 저장.
--
-- 05/18 - ID 정책 개정 반영:
--   - from_version_id / to_version_id 는 모두 UUID 문자열을 저장한다.
--   - "current" 특수값 의존을 줄였다:
--       기존: onDocumentModified가 to_version_id = "current"로 INSERT
--             → 시간이 지나면 "current"가 다른 콘텐츠를 가리키게 되어 캐시 의미가 변질
--       변경: onDocumentModified가 to_version_id = 새로 발급한 versionId(UUID)로 INSERT
--             → 캐시 항목의 의미가 영구 고정 (동일 (from, to) 페어는 항상 동일 콘텐츠를 가리킴)
--   - prepareVersionComparison이 호출자에게서 "current"를 받으면
--     documents.current_version_id를 조회해 즉시 구체 versionId로 변환 후 사용/저장.
--   - 따라서 본 테이블에 "current" 문자열이 새로 INSERT되는 일은 발생하지 않는다.
--     (과거 데이터 마이그레이션 시에만 "current" 값이 존재할 수 있음 → 의사코드 단계에선 신경 X)
--
--   타입 적합성:
--     UUID는 36자, "current"는 7자 → 36자 이상 컬럼이면 모두 수용.
--     기존 VARCHAR(512)은 과한 폭이지만 호환성 위해 유지.
--     실 운영에선 CHAR(36) 또는 VARCHAR(64)로 축소 검토 가능.
--
--   FK 미설정 이유:
--     - 마이그레이션 단계에서 "current" 특수값이 남아 있을 수 있음
--     - diff 캐시는 본질적으로 "버전이 삭제되면 캐시도 가비지 컬렉션" 모델이 자연스러움
--       (INSERT IGNORE + 주기적 정리 잡으로 충분, FK CASCADE보다 단순)
-- 코드 참조:
--   onDocumentModified         → INSERT (from=previousVersionId, to=새 versionId)  -- 모두 UUID
--   prepareVersionComparison   → SELECT (캐시 hit 시), INSERT (캐시 miss 시 계산 후 저장)
--   getVersionDiff             → SELECT 단일 (UI에서 직접 diff 조회)
CREATE TABLE version_diffs (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'diff 캐시 고유 ID',
    file_id         VARCHAR(255)    NOT NULL    COMMENT '대상 파일 ID (UUID, documents.file_id)',
    from_version_id VARCHAR(64)     NOT NULL    COMMENT '시작 버전 ID (UUID). 레거시 "current" 호환',
    to_version_id   VARCHAR(64)     NOT NULL    COMMENT '끝 버전 ID (UUID). 레거시 "current" 호환',
    diff_method     VARCHAR(16)     NOT NULL    COMMENT '계산 방식: myers | sha256 | binary',
    added_lines     INT             NOT NULL    DEFAULT 0 COMMENT '추가된 라인 수',
    deleted_lines   INT             NOT NULL    DEFAULT 0 COMMENT '삭제된 라인 수',
    summary         VARCHAR(512)    DEFAULT NULL COMMENT '요약 텍스트 (예: "+120 -45 lines")',
    hunks_json      LONGTEXT        DEFAULT NULL COMMENT 'Unified diff hunks (JSON 배열)',
    created_at      BIGINT          NOT NULL    COMMENT '생성 시각 (Unix timestamp)',

    PRIMARY KEY (id),
    UNIQUE INDEX idx_version_pair (file_id, from_version_id, to_version_id),
    INDEX idx_file_created (file_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='버전 간 diff 결과 캐시 (RD-SRS-9.4)';

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
-- 05/06 - Phase A-9: 다수 승인자 합의 모델 컬럼 추가
--   consensus_mode      : THRESHOLD(M of N) / UNANIMOUS(만장일치) / SEQUENTIAL(순차)
--   required_approvals  : THRESHOLD 모드의 최소 승인 수 (예: 5명 중 3명)
--   received_approvals  : 현재까지 누적된 승인 수
--   received_rejections : 현재까지 누적된 거절 수 (UNANIMOUS는 0이어야 진행)
CREATE TABLE approval_rules (
    id                  VARCHAR(255)    NOT NULL    COMMENT '규칙 고유 ID (UUID)',
    file_id             VARCHAR(255)    NOT NULL    COMMENT '대상 파일 ID',
    tag_pending         VARCHAR(255)    NOT NULL    COMMENT '대기 상태 태그 (under_review)',
    tag_approved        VARCHAR(255)    NOT NULL    COMMENT '승인 상태 태그 (approved)',
    tag_rejected        VARCHAR(255)    NOT NULL    COMMENT '거절 상태 태그 (rejected)',
    status              VARCHAR(16)     NOT NULL    DEFAULT 'OPEN' COMMENT '규칙 상태: OPEN(진행 중) / CLOSED(승인 또는 거절 완료) / CANCELLED(요청 취소, 04/30 Phase A-7 추가)',
    consensus_mode      VARCHAR(16)     NOT NULL    DEFAULT 'THRESHOLD' COMMENT '합의 모델 (05/06 A-9): THRESHOLD | UNANIMOUS | SEQUENTIAL',
    required_approvals  INT             NOT NULL    DEFAULT 1 COMMENT 'THRESHOLD 모드 최소 승인 수 (UNANIMOUS는 전체 승인자 수, SEQUENTIAL은 0)',
    received_approvals  INT             NOT NULL    DEFAULT 0 COMMENT '현재까지 누적된 승인 수',
    received_rejections INT             NOT NULL    DEFAULT 0 COMMENT '현재까지 누적된 거절 수',

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
--   processApprovalWorkflow REQUEST → INSERT (rule_id, entity_type, entity_id, sequence_order)
--   processApprovalDecision → SELECT rule_id WHERE entity_id = ? AND rule_id IN (...)
-- 05/06 - Phase A-9: sequence_order 추가 (SEQUENTIAL 모드의 승인 순서)
--   THRESHOLD/UNANIMOUS 모드에서는 sequence_order = 0 (의미 없음)
--   SEQUENTIAL 모드: 1, 2, 3... 순서대로 결정 진행
CREATE TABLE approval_rule_approvers (
    rule_id        VARCHAR(255)    NOT NULL    COMMENT '규칙 ID (approval_rules.id 참조)',
    entity_type    VARCHAR(64)     NOT NULL    DEFAULT 'user' COMMENT '엔티티 유형',
    entity_id      VARCHAR(255)    NOT NULL    COMMENT '승인자 ID',
    sequence_order INT             NOT NULL    DEFAULT 0 COMMENT 'SEQUENTIAL 모드의 승인 순서 (0=의미 없음, 1부터)',

    PRIMARY KEY (rule_id, entity_type, entity_id),
    INDEX idx_entity (entity_id),
    INDEX idx_rule_sequence (rule_id, sequence_order),
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
    user_id     VARCHAR(255)    NOT NULL    COMMENT '승인 판정의 기준이 되는 승인자 ID (effective approver). 위임 승인 시 실제 수행자가 아닌 원래 승인자를 저장. 실제 수행자(actual actor)는 comment에 기록. 전환 시 actual_user_id/effective_approver_id 컬럼 분리 권장.',
    action      VARCHAR(64)     NOT NULL    COMMENT '액션 태그 (approved / rejected / cancelled)',
    `timestamp` BIGINT          NOT NULL    COMMENT '수행 시각 (Unix timestamp)',
    comment     TEXT            DEFAULT NULL COMMENT '승인/거절 사유',

    PRIMARY KEY (id),
    INDEX idx_rule (rule_id),
    INDEX idx_user_action (user_id, action),
    -- 한 rule에서 한 effective approver는 한 번만 결정 가능 (중복 결정 DB 차원 방지)
    -- user_id = effective approver 기준 (위임 승인 시 원래 승인자 ID)
    -- [전환 시] effective_approver_id 컬럼 분리 후 UNIQUE(rule_id, effective_approver_id)로 변경
    UNIQUE INDEX uq_rule_user_decision (rule_id, user_id),
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
    -- 04/30 - Phase A-4: 중복 알림 방지 키
    --   형식: {eventType}:{fileId}:{userId}:{timeWindowBucket}
    --   timeWindowBucket = floor(timestamp / 300) (5분 단위 반올림)
    --   동일 이벤트가 5분 내에 같은 사용자에게 두 번 발생해도 INSERT IGNORE로 무시
    dedup_key       VARCHAR(255)    DEFAULT NULL COMMENT '중복 방지 키 (5분 단위 윈도우)',
    -- 05/06 - Phase ⑥ (의사코드 99% 보강): 읽음/안 읽음 추적
    --   NULL = 안 읽음, Unix timestamp = 사용자가 읽은 시각
    --   markNotificationRead가 갱신
    read_at         BIGINT          DEFAULT NULL COMMENT '읽은 시각 (NULL=안 읽음)',

    PRIMARY KEY (notification_id),
    INDEX idx_user_timestamp (`user`, `timestamp`),
    INDEX idx_object (object_type, object_id),
    INDEX idx_user_unread (`user`, read_at),
    UNIQUE INDEX idx_dedup (dedup_key)
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
-- 12. file_subscriptions: 파일 구독 (RD-SRS-9.9)
-- ============================================================
-- 04/30 - Phase A-2: 구독 관리 인프라 추가
--   목적: notifyStakeholders가 호출 시점에 targets를 외부에서 받지 않고
--         파일별 구독자 목록을 자동으로 조회할 수 있도록 함
--   설계: 파일 단위로 사용자가 구독을 등록/해제 가능. 일시정지 지원.
-- 코드 참조:
--   subscribeToFile      → INSERT/REPLACE (file_id, user_id, created_at, paused_until)
--   unsubscribeFromFile  → DELETE WHERE file_id = ? AND user_id = ?
--   pauseSubscription    → UPDATE paused_until WHERE file_id = ? AND user_id = ?
--   getSubscribers       → SELECT user_id WHERE file_id = ?
--                                AND (paused_until IS NULL OR paused_until < ?)
CREATE TABLE file_subscriptions (
    file_id      VARCHAR(255)    NOT NULL    COMMENT '대상 파일 ID',
    user_id      VARCHAR(255)    NOT NULL    COMMENT '구독자 ID',
    created_at   BIGINT          NOT NULL    COMMENT '구독 시작 시각 (Unix timestamp)',
    paused_until BIGINT          DEFAULT NULL COMMENT '일시정지 종료 시각 (NULL=활성)',

    PRIMARY KEY (file_id, user_id),
    INDEX idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='파일 구독 관리 (RD-SRS-9.9)';

-- ============================================================
-- 13. file_subscription_channels: 구독별 채널 설정 (RD-SRS-9.9)
-- ============================================================
-- 04/30 - Phase A-2: 채널을 1급 객체로 정규화
--   설계 의도: 사용자가 구독별로 여러 채널을 선택할 수 있고,
--             채널별 일시 비활성화(enabled), 채널별 통계/일괄 발송이 가능해야 함
--   대안 검토: CSV 문자열 저장은 단순하나 채널별 쿼리/통계 어렵고
--             기업 환경의 채널 권한 관리 확장에 부적합 → 정규화 채택
-- 코드 참조:
--   subscribeToFile  → INSERT (file_id, user_id, channel, enabled=1) for each channel
--   getSubscribers   → SELECT channel WHERE file_id = ? AND user_id = ? AND enabled = 1
CREATE TABLE file_subscription_channels (
    file_id  VARCHAR(255)    NOT NULL    COMMENT '대상 파일 ID',
    user_id  VARCHAR(255)    NOT NULL    COMMENT '구독자 ID',
    channel  VARCHAR(16)     NOT NULL    COMMENT '채널 (PUSH | EMAIL | WEB)',
    enabled  TINYINT         NOT NULL    DEFAULT 1 COMMENT '활성 여부 (1=수신, 0=일시 비활성)',

    PRIMARY KEY (file_id, user_id, channel),
    INDEX idx_channel (channel),
    CONSTRAINT fk_subch_subscription
        FOREIGN KEY (file_id, user_id)
        REFERENCES file_subscriptions(file_id, user_id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='구독자별 채널 설정 (RD-SRS-9.9)';

-- ============================================================
-- 14. notification_outbox: 알림 발송 큐 (Outbox 패턴, RD-SRS-9.9)
-- ============================================================
-- 04/30 - Phase A-X: 알림 발송 신뢰성 보장을 위한 Outbox 패턴
--   목적: 알림 DB 저장과 외부 채널 발송의 원자성 보장 + 발송 실패 시 자동 재시도
--   동작:
--     1) notifyStakeholders가 PENDING 상태로 outbox INSERT
--     2) 즉시 발송 시도 → 성공: status='SENT', 실패: status='PENDING' 유지
--     3) processOutboxQueue (백그라운드 잡)가 PENDING 항목을 주기적으로 재시도
--     4) 재시도 정책: 1분 → 5분 → 30분 → DLQ (3회 재시도 후 dead letter)
--   재시도 시각 계산: retry_after = now + calculateBackoff(retry_count)
--
-- [Java 전환 시] Spring @Scheduled(fixedDelay=60000) + @Retryable로 대체
--               또는 Spring Modulith Outbox / Eventuate Tram 같은 라이브러리 사용
-- 코드 참조:
--   notifyStakeholders   → INSERT (status='PENDING'), UPDATE status='SENT' on success
--   processOutboxQueue   → SELECT WHERE status='PENDING' AND retry_after <= now,
--                          UPDATE retry_count/retry_after on retry,
--                          UPDATE status='DLQ' when retry_count >= 3
CREATE TABLE notification_outbox (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '큐 항목 고유 ID',
    notification_id VARCHAR(255)    NOT NULL    COMMENT '연관 알림 ID (notifications.notification_id)',
    user_id         VARCHAR(255)    NOT NULL    COMMENT '수신자 ID',
    channel         VARCHAR(16)     NOT NULL    COMMENT '발송 채널 (PUSH | EMAIL | WEB)',
    payload         TEXT            NOT NULL    COMMENT '발송 본문 (메시지 텍스트)',
    status          VARCHAR(16)     NOT NULL    DEFAULT 'PENDING' COMMENT 'PENDING | PROCESSING | SENT | DLQ',
    retry_count     INT             NOT NULL    DEFAULT 0 COMMENT '현재까지 재시도 횟수',
    retry_after     BIGINT          NOT NULL    COMMENT '다음 재시도 시각 (Unix timestamp)',
    last_error      TEXT            DEFAULT NULL COMMENT '마지막 실패 사유',
    created_at      BIGINT          NOT NULL    COMMENT '큐 등록 시각',
    sent_at         BIGINT          DEFAULT NULL COMMENT '발송 완료 시각 (status=SENT일 때만)',
    -- 05/XX - 멀티 Worker 안전성: PROCESSING 상태 + claim lock
    --   processOutboxQueue가 row를 가져갈 때 PENDING → PROCESSING으로 UPDATE(claim)하고
    --   locked_by에 자신의 worker ID를 기록. 이후 locked_by 기준으로 본인 row만 처리.
    --   stale lock(5분 이상 PROCESSING 유지) 감지 시 PENDING으로 복원.
    locked_by       VARCHAR(36)     DEFAULT NULL COMMENT 'claim한 Worker ID (UUID). NULL=미잠금',
    locked_at       BIGINT          DEFAULT NULL COMMENT 'claim 시각 (stale lock 감지용, Unix timestamp)',

    PRIMARY KEY (id),
    INDEX idx_status_retry (status, retry_after),
    INDEX idx_notification (notification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='알림 발송 Outbox 큐 (RD-SRS-9.9, A-X)';

-- ============================================================
-- 15. retention_policies: 버전 보존 정책 (RD-SRS-9.10)
-- ============================================================
-- 05/06 - Phase A-10: 보존 정책 CRUD + 차등 적용
--   목적: 시스템 전체/사용자/폴더/파일 단위로 다른 보존 정책 적용
--   적용 우선순위 (구체성 우선, 결정 ②):
--     FILE > FOLDER > USER > GLOBAL
--     (가장 구체적인 정책 하나만 사용. cascade하여 머지하지 않음)
--   scope_id의 의미:
--     - GLOBAL: 무시 (NULL 또는 'global' 등 임의 값)
--     - USER:   user_id
--     - FOLDER: 폴더 경로 (예: "/projects/legal/")
--     - FILE:   file_id
-- 코드 참조:
--   createRetentionPolicy   → INSERT (scope_type, scope_id, params, is_active=1)
--   getRetentionPolicy      → SELECT id = ?
--   updateRetentionPolicy   → UPDATE WHERE id = ?
--   deactivatePolicy        → UPDATE is_active = 0 WHERE id = ?
--   deletePolicy            → DELETE WHERE id = ?
--   evaluatePolicy(fileId)  → SELECT 가장 구체적인 활성 정책 (cascade)
--   applyToAllFiles(policyId) → 백그라운드 잡: 정책 영향 파일 일괄 정리
CREATE TABLE retention_policies (
    id           VARCHAR(255)    NOT NULL    COMMENT '정책 고유 ID (UUID)',
    scope_type   VARCHAR(16)     NOT NULL    COMMENT 'GLOBAL | USER | FOLDER | FILE',
    scope_id     VARCHAR(512)    DEFAULT NULL COMMENT 'scope_type별 식별자 (GLOBAL은 NULL)',
    min_days     INT             NOT NULL    DEFAULT 0 COMMENT '최소 보관 일수 (0=제약 없음)',
    max_days     INT             NOT NULL    DEFAULT 0 COMMENT '최대 보관 일수 (0=무제한)',
    max_versions INT             NOT NULL    DEFAULT 0 COMMENT '최대 버전 수 (0=무제한)',
    auto_cleanup TINYINT         NOT NULL    DEFAULT 1 COMMENT '공간 부족 시 자동 정리 여부',
    is_active    TINYINT         NOT NULL    DEFAULT 1 COMMENT '활성 상태 (결정 ⑥: soft delete)',
    created_at   BIGINT          NOT NULL    COMMENT '생성 시각',
    updated_at   BIGINT          NOT NULL    COMMENT '마지막 수정 시각',

    PRIMARY KEY (id),
    INDEX idx_scope (scope_type, scope_id, is_active),
    INDEX idx_active_global (scope_type, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='버전 보존 정책 (RD-SRS-9.10)';

-- ============================================================
-- 16. user_roles: 사용자 역할 (RD-SRS-9.6/9.10 권한 체크)
-- ============================================================
-- 05/06 - Phase ② (의사코드 99% 보강): isAdmin 실제 로직 지원
--   목적: 정책 관리, DEPRECATED 복원, forceDelete 등 관리자 권한 검증의 데이터 소스
--   현재 모델 (Q2=A 결정): USER / ADMIN 두 가지로 단순화
--     - USER  : 기본 권한 (특별 행 불필요. user_roles에 row 없으면 USER 간주)
--     - ADMIN : 시스템 관리자
--   향후 세분화 가능 (의사코드 단계엔 미적용):
--     - EDITOR    : 문서 편집/수정 권한 강화
--     - APPROVER  : 모든 파일에 대한 승인자 자격
--     - AUDITOR   : 감사 로그 열람 전용
--     - SUPER_ADMIN : 정책 무시 가능 (forceDelete의 forceDelete 같은 메타 권한)
--   세분화 시 role 컬럼 값에 추가하고 isAdmin/hasRole 메서드만 보강하면 됨
-- 코드 참조:
--   isAdmin       → SELECT 1 WHERE user_id = ? AND role = 'ADMIN' LIMIT 1
--   assignRole    → INSERT (granted_by, granted_at 추적)
--   revokeRole    → DELETE WHERE user_id = ? AND role = ?
--   listAdmins    → SELECT user_id WHERE role = 'ADMIN'
-- [Java 전환 시] Spring Security GrantedAuthority로 매핑.
--                @PreAuthorize("hasRole('ADMIN')")로 메서드 보호 자동화.
CREATE TABLE user_roles (
    user_id     VARCHAR(255)    NOT NULL    COMMENT '사용자 ID',
    role        VARCHAR(32)     NOT NULL    COMMENT '역할 (USER | ADMIN)',
    granted_by  VARCHAR(255)    DEFAULT NULL COMMENT '권한 부여자 ID (감사 추적)',
    granted_at  BIGINT          NOT NULL    COMMENT '권한 부여 시각 (Unix timestamp)',

    PRIMARY KEY (user_id, role),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='사용자 역할 (RD-SRS-9.6/9.10 권한 체크)';

-- ============================================================
-- 17. approval_delegations: 승인 권한 위임 (RD-SRS-9.7)
-- ============================================================
-- 05/06 - Phase ① (의사코드 99% 보강): 승인 위임/대리 승인
--   목적: 승인자가 휴가/출장 등으로 부재 시 다른 사용자에게 권한 임시 양도
--   동작 (Q1=C 결정):
--     - 임시 위임: expires_at = 만료 시각 (Unix timestamp)
--     - 영구 위임: expires_at = NULL (위임자가 명시적으로 회수할 때까지)
--   권한 체크 흐름:
--     processApprovalDecision 호출 시 본인이 직접 승인자가 아니더라도
--     활성 위임(expires_at IS NULL OR expires_at > now)이 있고 위임자가 승인자이면 통과
--   주의:
--     - 같은 위임자가 여러 명에게 동시 위임 가능 (delegator → delegate1, delegate2)
--     - delegate(피위임자)가 결정하면 approval_activity.user_id에는 delegator(원래 승인자)를 기록
--       실제 수행자(delegate)는 comment에 "[actual actor {delegate}, delegated for {delegator}]" 형식으로 기록
--       이렇게 해야 SEQUENTIAL 순서 체크가 원래 승인자 기준으로 정상 동작함
--       (전환 시: actual_user_id / effective_approver_id 컬럼 분리로 근본 해결 권장)
--   감사 추적: 결정 시점에 활성 위임이 있었는지 approval_activity의 comment에 함께 기록
-- 코드 참조:
--   createDelegation     → INSERT (delegator_id, delegate_id, expires_at, reason)
--   revokeDelegation     → DELETE WHERE id = ?
--   getActiveDelegations → SELECT 활성 위임 목록
--   isUserDelegateOf     → SELECT 1 WHERE delegate_id = ? AND delegator_id = ? AND active
-- [Java 전환 시] @Scheduled로 만료된 위임 정리 잡 추가.
--                Spring Security 권한 체계와 통합 (위임이 일시적 GrantedAuthority 부여)
CREATE TABLE approval_delegations (
    id            VARCHAR(255)    NOT NULL    COMMENT '위임 고유 ID (UUID)',
    delegator_id  VARCHAR(255)    NOT NULL    COMMENT '위임자 (원래 승인자)',
    delegate_id   VARCHAR(255)    NOT NULL    COMMENT '피위임자 (대신 결정할 사용자)',
    reason        TEXT            DEFAULT NULL COMMENT '위임 사유 (휴가, 출장 등)',
    created_at    BIGINT          NOT NULL    COMMENT '위임 생성 시각',
    expires_at    BIGINT          DEFAULT NULL COMMENT '만료 시각 (NULL=영구)',

    PRIMARY KEY (id),
    INDEX idx_delegator (delegator_id, expires_at),
    INDEX idx_delegate (delegate_id, expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인 권한 위임 (RD-SRS-9.7)';

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