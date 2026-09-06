-- ============================================================
-- V8: 승인 워크플로 확장 — 다중 승인자 (RD-SRS-9.7 확장, 4-A)
--
--   변경 요지:
--     1) approval_requests.mode 추가 — 판정 방식 (ALL 전원 / MAJORITY 과반 / SEQUENTIAL 순차)
--     2) approval_request_approvers 신설 — 요청 1건 : 승인자 N명 (개인별 판정 보관)
--     3) 기존 단일 승인 데이터 이관 — "단일 승인 = ALL 방식 + 승인자 1명"과 동일하므로
--        기존 요청을 mode='ALL'로 두고 승인자를 자식 테이블로 복사한다.
--        이후 코드는 단일/다중 분기 없이 자식 테이블 한 갈래만 읽는다.
--
--   approval_requests.approver_id 는 레거시로 남긴다(과거 데이터 보존).
--   새 요청은 이 컬럼을 채우지 않으므로 NULL 허용으로 완화한다.
--
--   "문서당 열린 요청 하나"(open_marker UNIQUE)는 방식과 무관한 규칙이므로 유지.
-- 대상: MariaDB 10.11 LTS
-- ============================================================

ALTER TABLE approval_requests
    ADD COLUMN mode VARCHAR(16) NOT NULL DEFAULT 'ALL'
        COMMENT '판정 방식: ALL(전원) / MAJORITY(과반) / SEQUENTIAL(순차)',
    MODIFY COLUMN approver_id VARCHAR(255) NULL
        COMMENT '(레거시) V8 이전 단일 승인자 — V8부터 approval_request_approvers 사용';

CREATE TABLE IF NOT EXISTS approval_request_approvers (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '행 고유 ID',
    request_id  CHAR(36)        NOT NULL COMMENT 'approval_requests.id 참조',
    approver_id VARCHAR(255)    NOT NULL COMMENT '지정 승인자',
    seq         INT             NOT NULL DEFAULT 1 COMMENT '순번(SEQUENTIAL용, 1부터)',
    decision    VARCHAR(12)     NOT NULL DEFAULT 'PENDING'
                COMMENT '개인 판정: PENDING / APPROVED / REJECTED',
    decided_at  BIGINT          DEFAULT NULL COMMENT '개인 판정 시각(Unix epoch sec)',
    comment     TEXT            DEFAULT NULL COMMENT '개인 판정 사유',
    acted_by    VARCHAR(255)    DEFAULT NULL
                COMMENT '실제 처리자 — 본인이면 approver_id와 동일, 위임 처리 시 대리인(4-C)',

    PRIMARY KEY (id),
    -- 한 요청에 같은 승인자는 한 번만
    UNIQUE INDEX uq_request_approver (request_id, approver_id),
    INDEX idx_approver_pending (approver_id, decision),
    CONSTRAINT fk_appr_approver_request
        FOREIGN KEY (request_id) REFERENCES approval_requests(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인 요청별 승인자 목록과 개인 판정 (RD-SRS-9.7 다중 승인)';

-- 기존 단일 승인 요청 이관: 요청의 최종 상태를 그 승인자의 개인 판정으로 복원.
--   OPEN      → PENDING (아직 판정 전)
--   APPROVED  → APPROVED (그 승인자가 본인 처리)
--   REJECTED  → REJECTED
--   CANCELLED → PENDING (요청자가 취소 — 승인자는 판정한 적 없음)
INSERT INTO approval_request_approvers
        (request_id, approver_id, seq, decision, decided_at, acted_by)
SELECT  r.id,
        r.approver_id,
        1,
        CASE r.status WHEN 'APPROVED' THEN 'APPROVED'
                      WHEN 'REJECTED' THEN 'REJECTED'
                      ELSE 'PENDING' END,
        CASE WHEN r.status IN ('APPROVED','REJECTED') THEN r.decided_at ELSE NULL END,
        CASE WHEN r.status IN ('APPROVED','REJECTED') THEN r.approver_id ELSE NULL END
FROM    approval_requests r
WHERE   r.approver_id IS NOT NULL;
