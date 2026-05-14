package com.example.docver.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * activity 테이블 접근. 의사코드의 auditLog->logActivity 매핑.
 */
@Repository
@RequiredArgsConstructor
public class ActivityRepository {

    private final JdbcTemplate jdbc;

    /**
     * 활동 로그 INSERT. 의사코드 AuditLogService::logActivity와 동일.
     */
    public void log(String user, String fileId, String action, String message) {
        long now = System.currentTimeMillis() / 1000;
        jdbc.update(
                "INSERT INTO activity " +
                "(`timestamp`, `user`, affecteduser, app, subject, subjectparams, file, object_type, object_id) " +
                "VALUES (?, ?, ?, 'files', ?, ?, ?, 'files', ?)",
                now, user, user, action, message, fileId, fileId == null ? "" : fileId
        );
    }
}
