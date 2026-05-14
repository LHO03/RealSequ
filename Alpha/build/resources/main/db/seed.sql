-- ============================================================
-- 시연 시드 데이터
-- 시나리오 1: alice(작성자) → bob(승인자)
-- ============================================================

-- 상태 태그 초기 등록 (의사코드 setDocumentStatus가 자동 생성하지만 미리 등록하면 효율적)
INSERT INTO systemtag (id, name, visibility, editable) VALUES
    ('tag_draft',        'draft',        1, 1),
    ('tag_under_review', 'under_review', 1, 1),
    ('tag_approved',     'approved',     1, 1),
    ('tag_rejected',     'rejected',     1, 1),
    ('tag_deprecated',   'deprecated',   1, 1);

-- 시연 사용자는 별도 user 테이블 없이 문자열 ID로 시연
-- (의사코드도 동일하게 user_id를 외래 키 없이 문자열로 다룸)
-- 시연 시 사용할 ID:
--   alice  : 문서 작성자/요청자
--   bob    : 승인자
--   carol  : 추가 시연용 (필요 시)
