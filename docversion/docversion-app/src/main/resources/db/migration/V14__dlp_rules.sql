-- ============================================================
-- V14: DLP 탐지 규칙 테이블 (RD-SRS-5.4, 5.5)
--   선행 산출물은 규칙을 자바 클래스 상수(2세대) 또는 YAML 파일(3세대)에 두었다.
--   전자는 규칙 변경에 재배포가 필요하고, 후자는 파일이 파싱조차 되지 않는 상태로
--   저장소에 들어가 있었다(정규식 역슬래시가 YAML 스칼라에서 미이스케이프).
--
--   여기서는 규칙을 테이블로 옮긴다. 이유:
--     1) 무중단 규칙 갱신. 5.6(유형/분류 기준 관리)이 리얼시큐 Web 담당이므로
--        서버는 규칙 CRUD API를 열어주어야 하는데, DB에 있어야 자연스럽다.
--     2) 이스케이프 계층이 하나 사라진다. 컬럼에는 정규식을 원형 그대로 넣는다.
--     3) 변경 이력 추적. 오탐 신고 시 "언제 누가 이 규칙을 바꿨나"를 볼 수 있다.
--     4) 구조가 retention_policies(V5)와 동형이라 관리 기능을 복제할 수 있다.
--
--   성능 주의: 문서마다 이 테이블을 읽지 않는다. 시동 시 1회 적재 후
--   Pattern.compile()한 객체를 메모리에 보관하고, 규칙 변경 API 호출 시에만
--   재적재하여 교체한다. DB는 저장소일 뿐 탐지 경로에 있지 않다.
-- ============================================================

-- ------------------------------------------------------------
-- dlp_patterns: 정규식 규칙 (RD-SRS-5.4)
--   판정은 점수 합산 방식이다. 탐지된 항목의 점수를 더해 임계값과 비교한다.
--   5.5(머신러닝 기반 탐지)를 이번 범위에서 제외하면서 선행 구현의
--   "정규식 매칭 존재 OR 휴리스틱 점수 초과" 논리합이 성립하지 않으므로,
--   단독 확정 항목(주민번호)과 누적 항목(이메일)을 같은 척도로 다루기 위해
--   점수제를 택했다.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlp_patterns (
    id                VARCHAR(36)  NOT NULL COMMENT '규칙 고유 ID(UUID)',
    pattern_name      VARCHAR(64)  NOT NULL COMMENT '규칙 이름(SSN, CREDIT_CARD 등). Finding.patternName과 일치',
    display_name      VARCHAR(128) NOT NULL COMMENT '화면 표시명(주민등록번호 등)',
    regex             VARCHAR(1024) NOT NULL COMMENT '자바 정규식. 원형 그대로 저장(YAML 이스케이프 불필요)',
    severity          VARCHAR(8)   NOT NULL DEFAULT 'MEDIUM' COMMENT 'HIGH | MEDIUM | LOW',

    -- 점수: 검증기 유무에 따라 두 값을 둔다.
    --   체크섬을 통과하면 확정에 가까우므로 높은 점수(score_verified),
    --   형식만 맞으면 낮은 점수(score)를 준다. 둘 다 임계값을 넘도록 설계하면
    --   오타가 섞인 실제 주민번호를 놓치지 않으면서 심각도는 구분할 수 있다.
    score             INT          NOT NULL DEFAULT 0 COMMENT '기본 점수(검증기 없음 또는 검증 실패 시)',
    score_verified    INT          NOT NULL DEFAULT 0 COMMENT '검증기 통과 시 점수. validator가 NULL이면 미사용',

    validator         VARCHAR(32)  DEFAULT NULL COMMENT '추가 검증기 이름(SSN_CHECKSUM, LUHN). NULL이면 정규식만 적용',

    -- 문맥 조건: 정규식만으로는 과탐이 심한 규칙에 사용한다.
    -- 계좌번호가 대표적이다. 자릿수만 보면 주문번호·사번·도서번호가 모두 걸린다.
    -- 매칭 위치 앞뒤 일정 범위에 이 정규식이 함께 나타날 때만 인정한다.
    context_regex     VARCHAR(512) DEFAULT NULL COMMENT '문맥 조건 정규식. NULL이면 조건 없음',
    context_window    INT          NOT NULL DEFAULT 40 COMMENT '문맥 탐색 범위(매칭 앞뒤 문자 수)',

    -- 같은 유형이 반복될 때 점수가 무한히 쌓이는 것을 막는다.
    -- 전화번호 100개짜리 명부가 곧바로 최고 심각도가 되지 않도록 한다.
    max_hits_scored   INT          NOT NULL DEFAULT 3 COMMENT '점수에 반영할 최대 탐지 건수(0=무제한)',

    mask_keep_prefix  INT          NOT NULL DEFAULT 0 COMMENT '마스킹 시 앞에서 남길 문자 수',
    mask_keep_suffix  INT          NOT NULL DEFAULT 0 COMMENT '마스킹 시 뒤에서 남길 문자 수',

    description       VARCHAR(512) DEFAULT NULL COMMENT '규칙 설명',
    is_active         TINYINT      NOT NULL DEFAULT 1 COMMENT '활성 상태(0=비활성, soft delete)',
    created_at        BIGINT       NOT NULL COMMENT '생성 시각(Unix epoch sec)',
    updated_at        BIGINT       NOT NULL COMMENT '마지막 수정 시각(Unix epoch sec)',
    updated_by        VARCHAR(255) DEFAULT NULL COMMENT '마지막 수정자',

    PRIMARY KEY (id),
    UNIQUE INDEX uq_pattern_name (pattern_name),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='DLP 정규식 탐지 규칙 (RD-SRS-5.4)';

-- ------------------------------------------------------------
-- dlp_keywords: 키워드 규칙 (RD-SRS-5.5)
--   5.5는 이번 구현 범위에서 제외되었다(박사님 지시: 클래스 골격만 유지).
--   스키마만 미리 만들어 두어 나중에 마이그레이션 없이 채울 수 있게 한다.
--   현재는 비어 있으며 어떤 판정에도 참여하지 않는다.
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS dlp_keywords (
    id          VARCHAR(36)  NOT NULL COMMENT '키워드 고유 ID(UUID)',
    token       VARCHAR(128) NOT NULL COMMENT '표지어(대외비, 기밀 등)',
    weight      INT          NOT NULL DEFAULT 1 COMMENT '가중치',
    is_active   TINYINT      NOT NULL DEFAULT 1 COMMENT '활성 상태',
    created_at  BIGINT       NOT NULL COMMENT '생성 시각(Unix epoch sec)',
    updated_at  BIGINT       NOT NULL COMMENT '마지막 수정 시각(Unix epoch sec)',

    PRIMARY KEY (id),
    UNIQUE INDEX uq_token (token),
    INDEX idx_active (is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='DLP 키워드 규칙 (RD-SRS-5.5 — 이번 범위 제외, 스키마만 확보)';

-- ============================================================
-- 초기 규칙 적재
--   임계값 50 기준으로 배점하였다.
--     주민등록번호 / 신용카드  : 단독 확정 (100)
--     계좌번호                 : 문맥 조건 통과 시 확정에 준함 (80)
--     전화번호                 : 3건이면 임계값 도달 (20)
--     이메일                   : 누적으로만 의미 (10)
--
--   정규식은 자바 문법 기준이며 원형 그대로 저장한다.
--   MariaDB 문자열 리터럴에서 백슬래시는 이스케이프 문자이므로 두 번 쓴다.
--   (테이블에 저장되는 실제 값은 한 번짜리 백슬래시다.)
-- ============================================================

-- 주민등록번호 (RD-SRS-5.4)
--   생년월일 6자리 + 성별코드 + 6자리.
--   선임자 규칙은 \\b\\d{6}[- ]?\\d{7}\\b 로 자릿수만 보아
--   999999-9999999 같은 더미값이나 임의 숫자열을 모두 탐지했다.
--   여기서는 월(01-12)·일(01-31)과 성별코드를 검증하여 과탐을 줄인다.
--
--   성별코드: 1,2=1900년대 내국인 / 3,4=2000년대 내국인
--             5,6=1900년대 외국인 / 7,8=2000년대 외국인
--             9,0=1800년대(생존자 사실상 없음 → 제외)
--   외국인등록번호도 민감정보이므로 1-8을 모두 포함한다.
INSERT INTO dlp_patterns
 (id, pattern_name, display_name, regex, severity, score, score_verified, validator,
  context_regex, context_window, max_hits_scored, mask_keep_prefix, mask_keep_suffix,
  description, is_active, created_at, updated_at)
VALUES
 (UUID(), 'SSN', '주민등록번호',
  '\\b(\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01]))[- ]?([1-8]\\d{6})\\b',
  'HIGH', 60, 100, 'SSN_CHECKSUM',
  NULL, 40, 3, 7, 0,
  '생년월일·성별코드 형식 검증 후 체크섬 통과 시 100점, 형식만 맞으면 60점. 둘 다 임계값(50)을 넘으므로 오타가 섞인 실제 주민번호도 놓치지 않는다.',
  1, UNIX_TIMESTAMP(), UNIX_TIMESTAMP());

-- 신용카드번호 (RD-SRS-5.4)
--   2세대 규칙은 접두 4자리 + 4자리×3 + 3~4자리를 요구해 19~20자리가 되어
--   실제 16자리 카드번호를 전혀 탐지하지 못했다. 테스트에 카드 항목이 없어
--   11건 통과에도 발견되지 않은 결함이다.
--   Visa(4) / MasterCard(51-55) / Discover(6011) / Amex(34,37) 접두를 인정하고
--   Luhn 검증으로 과탐을 억제한다.
INSERT INTO dlp_patterns
 (id, pattern_name, display_name, regex, severity, score, score_verified, validator,
  context_regex, context_window, max_hits_scored, mask_keep_prefix, mask_keep_suffix,
  description, is_active, created_at, updated_at)
VALUES
 (UUID(), 'CREDIT_CARD', '신용카드번호',
  '\\b(?:4\\d{3}|5[1-5]\\d{2}|6011|3[47]\\d{2})[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b',
  'HIGH', 60, 100, 'LUHN',
  NULL, 40, 3, 4, 4,
  'Luhn 검증 통과 시 100점, 형식만 맞으면 60점.',
  1, UNIX_TIMESTAMP(), UNIX_TIMESTAMP());

-- 계좌번호 (RD-SRS-5.4)
--   자릿수만으로는 주문번호(20240115001), 사번, 도서번호가 모두 걸린다.
--   실측에서 확인된 과탐이므로 문맥 조건을 필수로 건다.
--   앞뒤 40자 안에 은행명이나 거래 관련 어휘가 있을 때만 인정한다.
INSERT INTO dlp_patterns
 (id, pattern_name, display_name, regex, severity, score, score_verified, validator,
  context_regex, context_window, max_hits_scored, mask_keep_prefix, mask_keep_suffix,
  description, is_active, created_at, updated_at)
VALUES
 (UUID(), 'BANK_ACCOUNT', '계좌번호',
  '\\b\\d{2,6}[- ]\\d{2,6}[- ]\\d{2,6}\\b',
  'MEDIUM', 80, 80, NULL,
  '(국민|신한|우리|하나|농협|기업|씨티|SC제일|카카오뱅크|케이뱅크|토스뱅크|수협|새마을금고|신협|우체국|산업|대구|부산|경남|광주|전북|제주|계좌|예금주|입금|송금|이체|account)',
  40, 3, 0, 0,
  '문맥 조건 필수. 은행명 또는 거래 어휘가 앞뒤 40자 안에 있을 때만 탐지하여 주문번호·사번 오탐을 억제한다.',
  1, UNIX_TIMESTAMP(), UNIX_TIMESTAMP());

-- 휴대전화번호 (RD-SRS-5.4 "개인정보 등")
--   선임자 규칙은 010만 인정하고 구분자도 하이픈·공백만 허용했다.
--   과거 문서(인사기록, 오래된 명부)에는 011/016/017/018/019가 남아 있으므로
--   01X 대역을 모두 포함한다. 유선번호(02, 031 등)는 자릿수가 들쭉날쭉해
--   과탐이 급증하므로 제외한다.
INSERT INTO dlp_patterns
 (id, pattern_name, display_name, regex, severity, score, score_verified, validator,
  context_regex, context_window, max_hits_scored, mask_keep_prefix, mask_keep_suffix,
  description, is_active, created_at, updated_at)
VALUES
 (UUID(), 'PHONE', '휴대전화번호',
  '\\b01[0-9][-. ]?\\d{3,4}[-. ]?\\d{4}\\b',
  'LOW', 20, 20, NULL,
  NULL, 40, 3, 3, 4,
  '단독으로는 임계값 미달. 3건 이상이면 60점으로 민감 판정.',
  1, UNIX_TIMESTAMP(), UNIX_TIMESTAMP());

-- 이메일 주소 (RD-SRS-5.4 "개인정보 등")
--   @ 앞뒤와 최상위 도메인까지 확인한다. user@localhost 같은 내부 주소는 제외된다.
INSERT INTO dlp_patterns
 (id, pattern_name, display_name, regex, severity, score, score_verified, validator,
  context_regex, context_window, max_hits_scored, mask_keep_prefix, mask_keep_suffix,
  description, is_active, created_at, updated_at)
VALUES
 (UUID(), 'EMAIL', '이메일주소',
  '\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b',
  'LOW', 10, 10, NULL,
  NULL, 40, 3, 2, 0,
  '누적으로만 의미를 갖는다. 최상위 도메인을 요구해 내부 주소 오탐을 배제한다.',
  1, UNIX_TIMESTAMP(), UNIX_TIMESTAMP());
