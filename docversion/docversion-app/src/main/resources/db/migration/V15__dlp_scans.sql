-- ============================================================
-- V15: DLP 검사 작업 + 추출 텍스트 공유 (RD-SRS-5.1, 5.2)
--   두 가지를 함께 넣는다.
--     (1) 검사 작업/탐지 항목 테이블 — 탐지 결과의 영속화
--     (2) files_versions에 추출 텍스트 컬럼 — 텍스트 추출 단일화
--
--   (2)를 지금 넣는 이유: 현재 DiffJobWorker는 Tika로 본문을 추출해 diff를
--   계산한 뒤 그 텍스트를 버린다. DLP 워커를 그냥 추가하면 같은 100MB 문서를
--   두 번, 향후 6.x 모델 탐지가 더해지면 세 번 파싱하게 된다.
--   나중에 도입하려면 마이그레이션과 워커 두 개를 동시에 고쳐야 하고
--   이미 축적된 버전에 대한 소급 추출도 필요해지므로 지금 자리를 잡는다.
-- ============================================================

-- ------------------------------------------------------------
-- (1) files_versions 확장: 추출 텍스트 공유 (RD-SRS-5.1, 9.4 공용)
--   추출 결과 자체는 원본과 같은 저장 계층(objects/...)에 두고
--   여기에는 위치와 상태만 기록한다. 본문을 DB에 넣지 않는 이유는
--   100MB 문서의 추출 텍스트도 수십 MB에 달할 수 있기 때문이다.
--
--   text_status를 별도로 두는 것이 핵심이다.
--   추출 실패나 형식 미지원을 "빈 텍스트"로 처리하면 검사가 통과해버려
--   검사되지 않은 문서가 안전한 것으로 표시된다. 판정 불가와 구분해야 한다.
-- ------------------------------------------------------------
ALTER TABLE files_versions
    ADD COLUMN text_status  VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING|EXTRACTED|UNSUPPORTED|FAILED — UNSUPPORTED/FAILED는 안전을 의미하지 않음',
    ADD COLUMN text_key     VARCHAR(512) DEFAULT NULL
        COMMENT '추출 텍스트 저장 위치(objects/{fileId}/versions/{versionId}.txt). 미추출 시 NULL',
    ADD COLUMN text_chars   BIGINT       DEFAULT NULL
        COMMENT '추출 텍스트 길이(문자 수). 상한 초과 판단 및 통계용',
    ADD COLUMN text_error   VARCHAR(512) DEFAULT NULL
        COMMENT '추출 실패 사유';

-- 워커 폴링용: 아직 추출되지 않은 버전을 오래된 것부터 집는다.
CREATE INDEX idx_versions_text_status ON files_versions (text_status, `timestamp`);

-- 기존 버전 행은 DEFAULT 'PENDING'이 적용되므로 별도 백필이 필요 없다.
--   (ALTER TABLE ADD COLUMN 시 기존 행에 기본값이 채워진다.)
--   즉 이미 저장된 모든 버전이 추출 대기 상태가 되며, 워커가 순차 처리한다.
--   소급 추출 부담이 크면 운영 판단에 따라 아래를 수동 실행해
--   과거 버전을 대상에서 제외할 수 있다. 마이그레이션에는 포함하지 않는다.
--
--     UPDATE files_versions SET text_status = 'UNSUPPORTED'
--      WHERE `timestamp` < UNIX_TIMESTAMP('2026-08-18');

-- ------------------------------------------------------------
-- (2) dlp_scans: 검사 작업 (RD-SRS-5.1, 5.2)
--   V12 version_diffs와 동일한 상태 기계를 따른다.
--     PENDING → PROCESSING → COMPLETED | FAILED
--     PROCESSING에 오래 머문 작업은 PENDING으로 회수(워커 비정상 종료 대비)
--     FAILED는 수동 재시도로 PENDING 복귀 가능
--   이미 운용 중인 구조이므로 새로 설계하지 않고 복제한다.
--
--   scope로 전체 검사와 변경분 검사를 구분한다.
--     FULL  : 해당 버전 전체 텍스트. "이 문서는 지금 민감한가" — 판정의 정본
--     DELTA : 이전 버전 대비 추가된 줄. "이번 수정으로 새로 유입되었는가"
--   클라이언트가 차단 판단(5.3)에 쓰는 값은 FULL이다.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlp_scans (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '검사 고유 ID',
    file_id        CHAR(36)        NOT NULL COMMENT 'documents.file_id 참조',
    version_id     CHAR(36)        NOT NULL COMMENT 'files_versions.version_id 참조',
    scope          VARCHAR(8)      NOT NULL COMMENT 'FULL | DELTA',

    status         VARCHAR(16)     NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING|PROCESSING|COMPLETED|FAILED',

    -- 판정 결과. COMPLETED가 되기 전에는 NULL이다.
    --   UNDETERMINED를 verdict의 한 값으로 두어 "검사했으나 판정 불가"를
    --   "민감하지 않음"과 구분한다. status=COMPLETED이면서
    --   verdict=UNDETERMINED인 조합이 정상적으로 존재한다.
    verdict        VARCHAR(16)     DEFAULT NULL
        COMMENT 'SENSITIVE|NOT_SENSITIVE|UNDETERMINED',
    total_score    INT             DEFAULT NULL COMMENT '탐지 항목 점수 합계',
    threshold      INT             DEFAULT NULL COMMENT '적용된 임계값(결과 해석 근거로 함께 보관)',
    max_severity   VARCHAR(8)      DEFAULT NULL COMMENT '탐지 항목 중 최고 심각도',
    finding_count  INT             NOT NULL DEFAULT 0 COMMENT '탐지 항목 수',
    method         VARCHAR(32)     DEFAULT NULL COMMENT '판정 방법(RULE 등). 감사 추적용',
    note           VARCHAR(512)    DEFAULT NULL COMMENT '판정 불가 사유 등 부가 설명',

    attempts       INT             NOT NULL DEFAULT 0 COMMENT '검사 시도 횟수',
    last_error     VARCHAR(512)    DEFAULT NULL COMMENT '마지막 실패 사유',
    created_at     BIGINT          NOT NULL COMMENT '적재 시각(Unix epoch sec)',
    updated_at     BIGINT          NOT NULL COMMENT '상태 갱신 시각(워커 재점유 판단용)',

    PRIMARY KEY (id),
    -- 같은 버전·같은 범위의 검사는 한 건만 존재한다.
    -- 재검사는 새 행을 만들지 않고 기존 행을 PENDING으로 되돌린다.
    UNIQUE INDEX uq_version_scope (version_id, scope),
    INDEX idx_scan_status (status, updated_at),
    INDEX idx_file_created (file_id, created_at),
    CONSTRAINT fk_scans_version
        FOREIGN KEY (version_id) REFERENCES files_versions(version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='DLP 검사 작업 (RD-SRS-5.1, 5.2)';

-- ------------------------------------------------------------
-- (3) dlp_findings: 탐지 항목 (RD-SRS-5.4)
--   탐지된 원문은 저장하지 않는다. 선행 산출물의 MatchResult는 매칭 문자열을
--   그대로 보유했는데, 그 값이 DB나 로그로 흘러가면 탐지 결과 자체가
--   2차 유출 경로가 된다. 선행 문서(고도화 고려사항 4절)도 같은 우려를
--   제기했으나 자료 구조가 그 권고를 구현할 수 없는 형태였다.
--
--   match_offset/match_length는 1세대에 있다가 2세대에서 사라진 정보다.
--   "원문 대신 위치만 기록한다"는 정책을 구현하려면 반드시 필요하므로 되살린다.
--   (offset/length는 예약어 충돌 소지가 있어 match_ 접두를 붙였다.)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlp_findings (
    id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '탐지 항목 고유 ID',
    scan_id       BIGINT UNSIGNED NOT NULL COMMENT 'dlp_scans.id 참조',
    pattern_name  VARCHAR(64)     NOT NULL COMMENT '규칙 이름(dlp_patterns.pattern_name)',
    severity      VARCHAR(8)      NOT NULL COMMENT 'HIGH | MEDIUM | LOW',
    score         INT             NOT NULL DEFAULT 0 COMMENT '이 항목이 총점에 기여한 점수',
    verified      TINYINT         NOT NULL DEFAULT 0 COMMENT '검증기(체크섬 등) 통과 여부',
    match_offset  INT             NOT NULL COMMENT '검사 대상 텍스트에서의 시작 위치(0-기준)',
    match_length  INT             NOT NULL COMMENT '매칭 길이',
    masked_value  VARCHAR(256)    NOT NULL COMMENT '일부만 노출한 값. 원문 복원 불가 형태',
    scored        TINYINT         NOT NULL DEFAULT 1 COMMENT '점수 반영 여부(max_hits_scored 초과분은 0)',

    PRIMARY KEY (id),
    INDEX idx_scan (scan_id),
    INDEX idx_pattern (pattern_name),
    CONSTRAINT fk_findings_scan
        FOREIGN KEY (scan_id) REFERENCES dlp_scans(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='DLP 탐지 항목 — 원문 미보관 (RD-SRS-5.4)';
