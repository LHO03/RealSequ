package com.example.docver.repository;

import com.example.docver.model.VersionInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * files_versions 테이블 접근.
 * 의사코드의 INSERT/SELECT DB 호출을 JdbcTemplate으로 매핑.
 */
@Repository
@RequiredArgsConstructor
public class FileVersionRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<VersionInfo> ROW_MAPPER = (rs, rn) -> VersionInfo.builder()
            .versionId(rs.getString("version_id"))
            .fileId(rs.getString("file_id"))
            .userId(rs.getString("user_id"))
            .timestamp(rs.getLong("timestamp"))
            .size(rs.getLong("size"))
            .mimeType(rs.getString("mimetype"))
            .metadata(rs.getString("metadata"))
            .build();

    /**
     * 의사코드 createInitialVersion/onDocumentModified의 INSERT 호출 매핑.
     */
    public int insert(VersionInfo v) {
        return jdbc.update(
                "INSERT INTO files_versions " +
                "(version_id, file_id, user_id, `timestamp`, size, mimetype, metadata) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                v.getVersionId(), v.getFileId(), v.getUserId(),
                v.getTimestamp(), v.getSize(), v.getMimeType(), v.getMetadata()
        );
    }

    /**
     * 파일 소유자 조회 (최초 버전 작성자).
     * 의사코드 getDefaultStakeholders의 addOwner 람다와 동일.
     */
    public Optional<String> findOwnerByFileId(String fileId) {
        List<String> result = jdbc.queryForList(
                "SELECT user_id FROM files_versions " +
                "WHERE file_id = ? ORDER BY `timestamp` ASC LIMIT 1",
                String.class, fileId
        );
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /**
     * 마지막 수정자 조회.
     * 의사코드 getDefaultStakeholders의 addLastEditor 람다와 동일.
     */
    public Optional<String> findLastEditorByFileId(String fileId) {
        List<String> result = jdbc.queryForList(
                "SELECT user_id FROM files_versions " +
                "WHERE file_id = ? ORDER BY `timestamp` DESC LIMIT 1",
                String.class, fileId
        );
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /**
     * 파일의 전체 버전 목록 (최신순). 시연용 조회 API에서 사용.
     */
    public List<VersionInfo> findByFileIdDesc(String fileId, int limit, int offset) {
        return jdbc.query(
                "SELECT version_id, file_id, user_id, `timestamp`, size, mimetype, metadata " +
                "FROM files_versions WHERE file_id = ? " +
                "ORDER BY `timestamp` DESC LIMIT ? OFFSET ?",
                ROW_MAPPER, fileId, limit, offset
        );
    }
}
