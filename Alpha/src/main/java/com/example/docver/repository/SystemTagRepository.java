package com.example.docver.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * systemtag + systemtag_object_mapping 테이블 접근.
 * 의사코드 setDocumentStatus, getCurrentStatusTag의 DB 호출 매핑.
 */
@Repository
@RequiredArgsConstructor
public class SystemTagRepository {

    private final JdbcTemplate jdbc;

    /**
     * 파일의 현재 상태 태그명 조회.
     * 의사코드 getCurrentStatusTag (line 3277) 이식.
     * 빈 문자열 반환 = 새 파일 또는 상태 미지정.
     */
    public String findCurrentStatusTag(String fileId) {
        List<String> result = jdbc.queryForList(
                "SELECT st.name FROM systemtag_object_mapping som " +
                "JOIN systemtag st ON som.systemtagid = st.id " +
                "WHERE som.objectid = ? AND som.objecttype = 'files' " +
                "AND st.name IN ('draft', 'under_review', 'approved', 'rejected', 'deprecated')",
                String.class, fileId
        );
        return result.isEmpty() ? "" : result.get(0);
    }

    /**
     * 태그 ID 조회. 없으면 빈 문자열.
     */
    public String findTagIdByName(String tagName) {
        List<String> result = jdbc.queryForList(
                "SELECT id FROM systemtag WHERE name = ?",
                String.class, tagName
        );
        return result.isEmpty() ? "" : result.get(0);
    }

    /**
     * 파일에 태그 할당.
     * REPLACE INTO 패턴 — UNIQUE INDEX (objectid, objecttype) 충돌 시 자동 교체.
     * 의사코드 setDocumentStatus의 4-5단계와 동일 동작.
     */
    public void assignTag(String fileId, String tagId) {
        jdbc.update(
                "REPLACE INTO systemtag_object_mapping " +
                "(objectid, objecttype, systemtagid) VALUES (?, 'files', ?)",
                fileId, tagId
        );
    }
}
