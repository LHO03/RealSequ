-- ============================================================
-- V10: 문서 (소유자, 경로) 유일성 강제 — 07/12, I-1
--
--   배경: upload()의 "조회 후 없으면 생성"(check-then-act)은 같은 새 경로로
--   동시 업로드 2건이 오면 문서를 2개 만들 수 있다. current_path는
--   VARCHAR(1024)+utf8mb4라 직접 UNIQUE를 걸 수 없으므로(InnoDB 키 길이 3072B
--   한계 — V1의 prefix 인덱스와 같은 이유), 경로의 SHA-256 해시 컬럼에
--   UNIQUE를 건다.
--
--   soft delete 공존 규약: path_hash는 "살아 있는 문서"만 값을 가진다.
--   삭제된 문서는 NULL(UNIQUE는 NULL 다중 허용)이라 같은 경로의 재생성을
--   막지 않는다. 추후 문서 soft delete를 구현할 때는 deleted_at 설정과 함께
--   path_hash = NULL 로 바꿔야 한다 (open_marker와 같은 마커 기법).
-- ============================================================

ALTER TABLE documents
    ADD COLUMN path_hash CHAR(64) DEFAULT NULL
        COMMENT 'SHA-256(current_path). 살아 있는 문서만 값 보유 — UNIQUE(owner, path_hash)로 경로 중복 차단'
        AFTER current_path;

-- 기존 살아 있는 문서 백필 (SHA2 소문자 16진수 = Java MessageDigest 표기와 동일)
UPDATE documents
SET path_hash = SHA2(current_path, 256)
WHERE deleted_at IS NULL;

-- 주의: 과거 경합으로 (owner, path) 중복 행이 이미 존재하면 이 제약 생성이 실패한다.
--   개발 DB에서 발생 시: 중복 중 오래된 행을 정리(또는 deleted_at 마킹 + path_hash NULL)
--   후 재기동하면 Flyway가 V10을 재시도한다.
ALTER TABLE documents
    ADD UNIQUE KEY uq_documents_owner_path (owner_user_id, path_hash);
