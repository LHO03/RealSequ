-- ============================================================
-- V9: 승인 위임 (RD-SRS-9.7 확장, 4-C)
--
--   승인자가 부재(휴가 등)일 때 기간을 정해 대리인에게 승인 권한을 넘긴다.
--   - 위임은 권한의 "추가"이지 박탈이 아니다: 위임자 본인도 여전히 판정할 수 있다.
--   - 대리 판정 시 approval_request_approvers.acted_by 에 실제 처리자(대리인)가 남는다
--     (V8에서 미리 마련한 컬럼) — 기록상 "지정 승인자 A (실제 처리: B)".
--   - 위임자 1인당 활성 위임은 1건: active_marker 기법(open_marker와 동일).
--       활성이면 active_marker = delegator_id (UNIQUE 충돌로 두 번째 차단)
--       해지/만료되면 NULL (NULL은 UNIQUE에서 중복으로 안 침)
-- 대상: MariaDB 10.11 LTS
-- ============================================================

CREATE TABLE IF NOT EXISTS approval_delegations (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '위임 고유 ID',
    delegator_id  VARCHAR(255)    NOT NULL COMMENT '위임자(원 승인자)',
    delegate_id   VARCHAR(255)    NOT NULL COMMENT '대리인',
    starts_at     BIGINT          NOT NULL COMMENT '위임 시작(Unix epoch sec)',
    ends_at       BIGINT          NOT NULL COMMENT '위임 종료(Unix epoch sec)',
    active_marker VARCHAR(255)    DEFAULT NULL
                  COMMENT '활성이면 delegator_id, 해지/만료 시 NULL. 위임자당 활성 1건 강제',
    created_at    BIGINT          NOT NULL COMMENT '설정 시각',
    revoked_at    BIGINT          DEFAULT NULL COMMENT '해지/만료 처리 시각',

    PRIMARY KEY (id),
    UNIQUE INDEX uq_active_per_delegator (active_marker),
    INDEX idx_delegate (delegate_id),
    INDEX idx_ends (ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='승인 위임 (RD-SRS-9.7 확장) — 기간제 대리 승인 권한';
