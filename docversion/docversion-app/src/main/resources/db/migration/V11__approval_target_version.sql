-- ============================================================
-- V11: 승인 요청을 특정 버전에 귀속 (RD-SRS-9.7 정합성 — 클러스터 1, P0-1)
--
--   문제: 기존 approval_requests는 file_id 단위였다. 승인자가 v2를 검토하는 사이
--         소유자가 v3를 올리면, "이 문서 승인됨"만 기록되어 검토하지 않은 v3에
--         승인 도장이 찍히는 정합성 붕괴가 가능했다.
--   해결: 요청 생성 시점의 현재 버전(current_version_id)을 target_version_id에 고정한다.
--         승인 확정 직전 target == current 를 재확인해(이중 방어), 다르면 승인하지 않고
--         요청을 STALE로 종료한다. (정책 A: 열린 요청 동안 업로드를 막으므로 정상
--         경로로는 이 불일치가 발생하지 않지만, 수동 경로·경합 대비 방어선으로 둔다.)
--
--   컬럼은 NULL 허용으로 추가한다:
--     - 과거에 종료된 요청(APPROVED/REJECTED/CANCELLED)은 이력용이라 NULL이어도 무해.
--     - 현재 열린(OPEN) 요청만 documents.current_version_id로 백필한다.
--     - 신규 요청은 애플리케이션(ApprovalService.request)이 항상 채운다.
-- 대상: MariaDB 10.11 LTS
-- ============================================================

ALTER TABLE approval_requests
    ADD COLUMN target_version_id CHAR(36) DEFAULT NULL
        COMMENT '승인 대상으로 고정된 버전 ID(요청 생성 시점의 current_version_id). 신규 요청 필수, 과거 이력은 NULL 허용';

-- 기존 열린 요청 백필: 요청이 걸린 문서의 현재 버전을 대상으로 고정.
UPDATE approval_requests r
JOIN documents d ON d.file_id = r.file_id
SET r.target_version_id = d.current_version_id
WHERE r.status = 'OPEN'
  AND r.target_version_id IS NULL;
