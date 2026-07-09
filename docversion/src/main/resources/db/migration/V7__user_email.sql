-- =============================================================
-- V7: 사용자 이메일 (RD-SRS-9.9 외부 발송 채널 — EMAIL)
--   알림의 실제 이메일 발송을 위해 수신 주소를 계정에 둔다.
--   NULL 허용: 이메일이 없는 계정은 인앱(WEB) 알림만 받는다.
--   기존 데모 계정은 {id}@docversion.local 로 채워 데모가 바로 동작하게 한다.
-- =============================================================

ALTER TABLE users
    ADD COLUMN email VARCHAR(320) NULL COMMENT '알림 수신 이메일 (NULL=인앱만)';

UPDATE users
   SET email = CONCAT(user_id, '@docversion.local')
 WHERE email IS NULL;
