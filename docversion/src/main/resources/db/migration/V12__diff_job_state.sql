-- ============================================================
-- V12: diff 캐시 → 내구성 있는 작업 상태 기계 (P1c, RD-SRS-9.4)
--   기존: onDocumentModified 후처리에서 요청 스레드가 동기로 diff를 계산하고,
--         실패하면 예외를 삼켜 캐시가 영영 비었다(재시도·가시성 없음).
--   변경: 수정 시 PENDING 작업을 적재하고, 백그라운드 워커가 PROCESSING을 거쳐
--         COMPLETED/FAILED로 전이한다. 실패는 attempts로 재시도하고, 재요청도 가능.
-- ============================================================

-- 상태/재시도/오류/갱신시각 컬럼 추가.
-- 이미 적재된 캐시 행은 완료 결과이므로 status 기본값 COMPLETED로 남는다.
ALTER TABLE version_diffs
    ADD COLUMN status     VARCHAR(16)  NOT NULL DEFAULT 'COMPLETED'
        COMMENT 'PENDING|PROCESSING|COMPLETED|FAILED',
    ADD COLUMN attempts   INT          NOT NULL DEFAULT 0   COMMENT '계산 시도 횟수',
    ADD COLUMN last_error VARCHAR(512) DEFAULT NULL          COMMENT '마지막 실패 사유',
    ADD COLUMN updated_at BIGINT       NOT NULL DEFAULT 0    COMMENT '상태 갱신 시각(워커 재점유 판단용)';

-- 기존 행 updated_at 백필(= created_at).
UPDATE version_diffs SET updated_at = created_at WHERE updated_at = 0;

-- 계산 전 PENDING 행은 diff_method가 아직 없으므로 NULL 허용으로 완화.
ALTER TABLE version_diffs
    MODIFY COLUMN diff_method VARCHAR(16) DEFAULT NULL
        COMMENT 'myers | myers_extracted | sha256 | binary (PENDING 동안 NULL)';

-- 워커 폴링 인덱스: 상태별 + 오래된 것 우선.
CREATE INDEX idx_diff_status ON version_diffs (status, updated_at);
