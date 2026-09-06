-- ============================================================
-- V3: 승인 워크플로 (RD-SRS-9.7) — 단일 승인자
--   원본 Schema.sql의 approval_rules/approval_activity 구조를 따르되,
--   단일 승인자에 필요한 만큼만 둔다. 다중 승인자(합의 모드)·위임은 후속 확장.
--
--   approval_requests : 승인 요청 1건 (원본 approval_rules 대응)
--     status: OPEN(대기) / APPROVED / REJECTED / CANCELLED
--     "문서당 열린(OPEN) 요청 하나"를 DB 차원에서 보장하기 위해 open_marker 사용.
--       - OPEN일 때  open_marker = file_id  (UNIQUE 충돌로 두 번째 OPEN 차단)
--       - 닫히면      open_marker = NULL     (NULL은 UNIQUE에서 중복으로 안 침)
--     MariaDB는 부분 유니크 인덱스를 지원하지 않으므로 이 기법을 사용.
--
--   approval_activity : 승인/반려/취소 판정 이력 (사유 포함)
-- 대상: MariaDB 10.11 LTS
-- ============================================================

CREATE TABLE IF NOT EXISTS approval_requests (
    id            CHAR(36)        NOT NULL COMMENT '요청 고유 ID(UUID)',
    file_id       CHAR(36)        NOT NULL COMMENT 'documents.file_id 참조',
    requester_id  VARCHAR(255)    NOT NULL COMMENT '요청자(문서를 검토 제출한 사용자)',
    approver_id   VARCHAR(255)    NOT NULL COMMENT '지정된 단일 승인자',
    status        VARCHAR(16)     NOT NULL DEFAULT 'OPEN'
                  COMMENT 'OPEN(대기) / APPROVED / REJECTED / CANCELLED',
    open_marker   CHAR(36)        DEFAULT NULL
                  COMMENT 'OPEN일 때 file_id, 닫히면 NULL. 문서당 OPEN 하나 강제용',
    created_at    BIGINT          NOT NULL COMMENT '요청 생성 시각(Unix epoch sec)',
    decided_at    BIGINT          DEFAULT NULL COMMENT '판정(승인/반려/취소) 시각',

    PRIMARY KEY (id),
    -- 같은 문서에 OPEN 요청은 하나만: open_marker(=file_id)에 UNIQUE.
    UNIQUE INDEX uq_open_per_file (open_marker),
    INDEX idx_file_status (file_id, status),
    INDEX idx_approver (approver_id, status),
    CONSTRAINT fk_approval_document
        FOREIGN KEY (file_id) REFERENCES documents(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인 요청 (RD-SRS-9.7, 단일 승인자)';

CREATE TABLE IF NOT EXISTS approval_activity (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '이력 고유 ID',
    request_id  CHAR(36)        NOT NULL COMMENT 'approval_requests.id 참조',
    actor_id    VARCHAR(255)    NOT NULL COMMENT '판정 수행자',
    action      VARCHAR(16)     NOT NULL COMMENT 'REQUESTED / APPROVED / REJECTED / CANCELLED',
    comment     TEXT            DEFAULT NULL COMMENT '사유',
    `timestamp` BIGINT          NOT NULL COMMENT '수행 시각(Unix epoch sec)',

    PRIMARY KEY (id),
    INDEX idx_request (request_id, `timestamp`),
    CONSTRAINT fk_approval_activity_request
        FOREIGN KEY (request_id) REFERENCES approval_requests(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인/반려/취소 이력 (RD-SRS-9.7)';
