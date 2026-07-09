-- ============================================================
-- V6: 사용자 계정 + 역할 (인증 1단계)
--   users      : 로그인 계정 저장소(아이디 + 비밀번호 해시 + 표시명).
--                비밀번호는 평문이 아니라 BCrypt 해시로만 저장한다.
--   user_roles : 사용자 역할(USER/ADMIN). 원본 Schema.sql 구조를 따른다.
--                user_roles에 행이 없으면 기본 USER로 간주(애플리케이션 규칙).
--   기본 계정(alice/bob/admin)은 기동 시 DefaultAccountsInitializer가 생성한다
--   (BCrypt 해시 생성을 SQL에서 할 수 없으므로 애플리케이션에서 시드).
-- 대상: MariaDB 10.11 LTS
-- ============================================================

CREATE TABLE IF NOT EXISTS users (
    user_id       VARCHAR(255) NOT NULL COMMENT '로그인 ID(=기존 userId 문자열과 동일 의미)',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 비밀번호 해시',
    display_name  VARCHAR(255) DEFAULT NULL COMMENT '표시 이름',
    enabled       TINYINT      NOT NULL DEFAULT 1 COMMENT '계정 활성 여부',
    created_at    BIGINT       NOT NULL COMMENT '생성 시각(Unix epoch sec)',

    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='사용자 계정 (인증 1단계)';

CREATE TABLE IF NOT EXISTS user_roles (
    user_id    VARCHAR(255) NOT NULL COMMENT '사용자 ID',
    role       VARCHAR(32)  NOT NULL COMMENT '역할 (USER | ADMIN)',
    granted_by VARCHAR(255) DEFAULT NULL COMMENT '권한 부여자 ID(감사 추적)',
    granted_at BIGINT       NOT NULL COMMENT '권한 부여 시각(Unix epoch sec)',

    PRIMARY KEY (user_id, role),
    INDEX idx_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='사용자 역할 (RD-SRS-9.6/9.10 권한 체크)';
