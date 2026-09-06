package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 알림 (RD-SRS-9.9) 데이터 접근 — 인앱 알림 + 구독 + 아웃박스.
 */
@Mapper
public interface NotificationMapper {

    // ---------- 알림 ----------
    /** 알림 저장(INSERT IGNORE: dedup_key 중복이면 무시). @return 1=신규, 0=중복무시 */
    int insertNotificationIgnore(@Param("notificationId") String notificationId,
                                 @Param("user") String user,
                                 @Param("timestamp") long timestamp,
                                 @Param("objectType") String objectType,
                                 @Param("objectId") String objectId,
                                 @Param("subject") String subject,
                                 @Param("message") String message,
                                 @Param("dedupKey") String dedupKey);

    List<Map<String, Object>> listByUser(@Param("user") String user,
                                         @Param("unreadOnly") boolean unreadOnly,
                                         @Param("limit") int limit);

    int countUnread(@Param("user") String user);

    /** 읽음 처리(본인 알림만). @return 영향 행 수 */
    int markRead(@Param("notificationId") String notificationId,
                 @Param("user") String user,
                 @Param("readAt") long readAt);

    // ---------- 구독 ----------
    int subscribeIgnore(@Param("fileId") String fileId,
                        @Param("userId") String userId,
                        @Param("createdAt") long createdAt);

    int unsubscribe(@Param("fileId") String fileId, @Param("userId") String userId);

    List<String> listSubscribers(@Param("fileId") String fileId);

    // ---------- 아웃박스 ----------
    int insertOutbox(@Param("notificationId") String notificationId,
                     @Param("userId") String userId,
                     @Param("channel") String channel,
                     @Param("payload") String payload,
                     @Param("retryAfter") long retryAfter,
                     @Param("createdAt") long createdAt);

    /** PENDING 중 발송 시각이 된 항목 목록. */
    List<Map<String, Object>> findSendable(@Param("now") long now, @Param("limit") int limit);

    /** claim: PENDING→PROCESSING (성공 시 1). 다른 워커가 이미 가져갔으면 0. */
    /** 07/12 - I-4: 고아 PROCESSING(locked_at &lt; cutoff)을 PENDING으로 회수. 회수 건수 반환. */
    int reclaimStale(@Param("cutoff") long cutoff);

    int claim(@Param("id") long id, @Param("workerId") String workerId, @Param("lockedAt") long lockedAt);

    int markSent(@Param("id") long id, @Param("sentAt") long sentAt);

    int markRetry(@Param("id") long id, @Param("retryCount") int retryCount,
                  @Param("retryAfter") long retryAfter, @Param("lastError") String lastError);

    int markDlq(@Param("id") long id, @Param("lastError") String lastError);

    List<Map<String, Object>> listOutbox(@Param("limit") int limit);
}
