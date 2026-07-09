-- ============================================================
-- V4: 알림 (RD-SRS-9.9) — 인앱 알림 + 구독 + 아웃박스(발송 신뢰성)
--   원본 Schema.sql의 notifications / file_subscriptions / notification_outbox를
--   따르되, 외부 채널 전용(푸시토큰·이메일큐·채널정규화)은 제외하고
--   인앱 알림과 아웃박스 신뢰성 패턴만 가져온다.
--
--   notifications        : 사용자별 인앱 알림 저장소 (read_at으로 읽음 추적)
--   file_subscriptions   : 파일별 구독자(=이해관계자) 목록
--   notification_outbox  : 발송 큐. 업무 변경과 같은 트랜잭션에서 PENDING으로 적재되어
--                          "기록됐는데 발송 안 됨" 어긋남을 구조적으로 차단.
--                          발송 워커가 PENDING을 claim하여 발송, 실패 시 백오프 재시도,
--                          3회 초과 시 DLQ(dead letter).
--
--   모든 식별은 사용자 ID 기준(이메일 주소 등 채널 정보를 직접 저장하지 않음) →
--   추후 실제 계정/채널 연동 시 발송 부품만 교체하면 됨.
-- 대상: MariaDB 10.11 LTS
-- ============================================================

CREATE TABLE IF NOT EXISTS notifications (
    notification_id CHAR(36)      NOT NULL COMMENT '알림 고유 ID(UUID)',
    `user`          VARCHAR(255)  NOT NULL COMMENT '수신자 사용자 ID',
    `timestamp`     BIGINT        NOT NULL COMMENT '알림 시각(Unix epoch sec)',
    object_type     VARCHAR(64)   NOT NULL COMMENT '객체 유형(document 등)',
    object_id       VARCHAR(255)  NOT NULL COMMENT '객체 ID(file_id 등)',
    subject         VARCHAR(255)  NOT NULL COMMENT '알림 제목(이벤트 유형)',
    message         TEXT          DEFAULT NULL COMMENT '알림 본문',
    dedup_key       VARCHAR(255)  DEFAULT NULL COMMENT '중복 방지 키(5분 윈도우). UNIQUE + INSERT IGNORE',
    read_at         BIGINT        DEFAULT NULL COMMENT '읽은 시각(NULL=안 읽음)',

    PRIMARY KEY (notification_id),
    UNIQUE INDEX uq_dedup (dedup_key),
    INDEX idx_user_unread (`user`, read_at),
    INDEX idx_user_time (`user`, `timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='인앱 알림 저장소 (RD-SRS-9.9)';

CREATE TABLE IF NOT EXISTS file_subscriptions (
    file_id     CHAR(36)      NOT NULL COMMENT '대상 파일 ID',
    user_id     VARCHAR(255)  NOT NULL COMMENT '구독자(이해관계자) ID',
    created_at  BIGINT        NOT NULL COMMENT '구독 시작 시각(Unix epoch sec)',

    PRIMARY KEY (file_id, user_id),
    INDEX idx_sub_user (user_id),
    CONSTRAINT fk_subscription_document
        FOREIGN KEY (file_id) REFERENCES documents(file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='파일 구독 관리 (RD-SRS-9.9)';

CREATE TABLE IF NOT EXISTS notification_outbox (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '큐 항목 고유 ID',
    notification_id CHAR(36)        NOT NULL COMMENT '연관 알림 ID',
    user_id         VARCHAR(255)    NOT NULL COMMENT '수신자 사용자 ID',
    channel         VARCHAR(16)     NOT NULL DEFAULT 'WEB' COMMENT '발송 채널(WEB=인앱 / EMAIL / PUSH)',
    payload         TEXT            NOT NULL COMMENT '발송 본문',
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING | PROCESSING | SENT | DLQ',
    retry_count     INT             NOT NULL DEFAULT 0 COMMENT '재시도 횟수',
    retry_after     BIGINT          NOT NULL COMMENT '다음 재시도 가능 시각(Unix epoch sec)',
    last_error      TEXT            DEFAULT NULL COMMENT '마지막 실패 사유',
    created_at      BIGINT          NOT NULL COMMENT '큐 등록 시각',
    sent_at         BIGINT          DEFAULT NULL COMMENT '발송 완료 시각',
    locked_by       VARCHAR(36)     DEFAULT NULL COMMENT 'claim한 워커 ID(NULL=미잠금)',
    locked_at       BIGINT          DEFAULT NULL COMMENT 'claim 시각(stale lock 감지용)',

    PRIMARY KEY (id),
    INDEX idx_status_retry (status, retry_after),
    INDEX idx_outbox_notification (notification_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='알림 발송 아웃박스 큐 (RD-SRS-9.9)';
