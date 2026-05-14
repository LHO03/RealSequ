package com.example.docver.repository;

import com.example.docver.model.NotificationInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * notifications 테이블 접근.
 * 의사코드의 notifyStakeholders, getUserNotifications, markNotificationRead 매핑.
 *
 * 시연 단순화 영역:
 *   - Outbox 큐 사용 안 함 (notifications 테이블에만 INSERT)
 *   - dedup_key 컬럼 미사용 (단일 흐름 시연이라 중복 없음)
 */
@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    private final JdbcTemplate jdbc;

    private static final RowMapper<NotificationInfo> ROW_MAPPER = (rs, rn) -> NotificationInfo.builder()
            .notificationId(rs.getString("notification_id"))
            .user(rs.getString("user"))
            .timestamp(rs.getLong("timestamp"))
            .objectType(rs.getString("object_type"))
            .objectId(rs.getString("object_id"))
            .subject(rs.getString("subject"))
            .message(rs.getString("message"))
            .readAt(rs.getObject("read_at", Long.class))  // NULL 가능
            .build();

    /**
     * 알림 INSERT. notifyStakeholders 내부에서 호출.
     */
    public void insert(String notificationId, String userId, long timestamp,
                       String objectId, String subject, String message) {
        jdbc.update(
                "INSERT INTO notifications " +
                "(notification_id, app, `user`, `timestamp`, object_type, object_id, subject, message) " +
                "VALUES (?, 'files', ?, ?, 'files', ?, ?, ?)",
                notificationId, userId, timestamp, objectId, subject, message
        );
    }

    /**
     * 사용자별 알림 조회.
     * 의사코드 getUserNotifications (line 2758) 이식.
     */
    public List<NotificationInfo> findByUserId(String userId, boolean unreadOnly, int limit, int offset) {
        // limit, offset clamp는 의사코드 라인 2765-2767과 동일
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;
        if (offset < 0) offset = 0;

        StringBuilder sql = new StringBuilder(
                "SELECT notification_id, `user`, `timestamp`, object_type, object_id, " +
                "       subject, message, read_at " +
                "FROM notifications WHERE `user` = ? "
        );
        if (unreadOnly) {
            sql.append("AND read_at IS NULL ");
        }
        sql.append("ORDER BY `timestamp` DESC LIMIT ? OFFSET ?");

        return jdbc.query(sql.toString(), ROW_MAPPER, userId, limit, offset);
    }

    /**
     * 안 읽은 알림 카운트.
     */
    public long countUnread(String userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE `user` = ? AND read_at IS NULL",
                Long.class, userId
        );
        return count == null ? 0 : count;
    }

    /**
     * 단건 읽음 처리.
     * 본인 알림만, 이미 읽은 건 변경 안 함 (최초 읽은 시각 보존).
     * 의사코드 markNotificationRead (line 2797).
     */
    public boolean markRead(String userId, String notificationId, long now) {
        int affected = jdbc.update(
                "UPDATE notifications SET read_at = ? " +
                "WHERE notification_id = ? AND `user` = ? AND read_at IS NULL",
                now, notificationId, userId
        );
        return affected > 0;
    }
}
