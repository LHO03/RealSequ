-- ============================================================
-- V5: 보존 정책 (RD-SRS-9.10)
--   원본 Schema.sql의 retention_policies를 그대로 옮긴다.
--   정책: 범위(GLOBAL/USER/FOLDER/FILE)별로 "버전을 얼마 동안 / 몇 개까지" 보관할지 정의.
--   정리: 정책에 따라 한도를 넘은 오래된 버전을 삭제하되, 보호 대상은 제외한다.
--     - 보호: 문서의 현재 버전(documents.current_version_id)은 절대 삭제하지 않는다.
--   관리자 권한 검증(isAdmin)은 인증 단계(Spring Security)에서 보강 예정 — 현재는 미적용.
--   is_active=0 은 soft delete(비활성). 실제 행 삭제 대신 비활성으로 처리.
-- 대상: MariaDB 10.11 LTS
-- ============================================================

CREATE TABLE IF NOT EXISTS retention_policies (
    id           VARCHAR(36)  NOT NULL COMMENT '정책 고유 ID(UUID)',
    scope_type   VARCHAR(16)  NOT NULL COMMENT 'GLOBAL | USER | FOLDER | FILE',
    scope_id     VARCHAR(512) DEFAULT NULL COMMENT 'scope_type별 식별자(GLOBAL은 NULL)',
    min_days     INT          NOT NULL DEFAULT 0 COMMENT '최소 보관 일수(이보다 최근 버전은 보호, 0=제약 없음)',
    max_days     INT          NOT NULL DEFAULT 0 COMMENT '최대 보관 일수(이보다 오래되면 정리 대상, 0=무제한)',
    max_versions INT          NOT NULL DEFAULT 0 COMMENT '최대 버전 수(최신 N개만 보관, 0=무제한)',
    auto_cleanup TINYINT      NOT NULL DEFAULT 1 COMMENT '스케줄러 자동 정리 대상 여부',
    is_active    TINYINT      NOT NULL DEFAULT 1 COMMENT '활성 상태(0=비활성, soft delete)',
    created_at   BIGINT       NOT NULL COMMENT '생성 시각(Unix epoch sec)',
    updated_at   BIGINT       NOT NULL COMMENT '마지막 수정 시각(Unix epoch sec)',

    PRIMARY KEY (id),
    INDEX idx_scope (scope_type, scope_id, is_active),
    INDEX idx_active_auto (is_active, auto_cleanup)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='버전 보존 정책 (RD-SRS-9.10)';
