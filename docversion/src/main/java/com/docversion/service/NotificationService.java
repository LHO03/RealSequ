package com.docversion.service;

import com.docversion.mapper.NotificationMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 알림 (RD-SRS-9.9).
 *
 * <p>핵심은 {@link #notifyStakeholders}이다. 이 메서드는 호출한 업무 메서드의
 * 트랜잭션 안에서 실행되어, 인앱 알림과 아웃박스 항목을 업무 변경과 <b>같은 트랜잭션</b>으로
 * 적재한다. 따라서 "변경은 기록됐는데 보낼 항목은 누락" 같은 어긋남이 생기지 않는다.
 * 실제 외부 발송은 아웃박스 워커가 별도로 처리하며, 실패 시 재시도한다.
 *
 * <p>모든 식별은 사용자 ID 기준이다(채널 정보 비저장).
 */
@Service
public class NotificationService {

    private final NotificationMapper mapper;
    private final UuidGenerator uuid;

    public NotificationService(NotificationMapper mapper, UuidGenerator uuid) {
        this.mapper = mapper;
        this.uuid = uuid;
    }

    /**
     * 파일의 구독자(이해관계자)에게 알림. 호출자의 트랜잭션 안에서 실행되어야 한다.
     * actorId(행위 당사자)는 자기 자신에게 알림이 가지 않도록 제외한다.
     */
    public void notifyStakeholders(String fileId, String subject, String message, String actorId) {
        long now = Instant.now().getEpochSecond();
        long bucket = now / 300; // 5분 윈도우 — 동일 이벤트 중복 알림 방지
        List<String> subscribers = mapper.listSubscribers(fileId);
        for (String u : subscribers) {
            if (u == null || u.equals(actorId)) {
                continue;
            }
            String notifId = uuid.newId();
            String dedupKey = subject + ":" + fileId + ":" + u + ":" + bucket;
            int inserted = mapper.insertNotificationIgnore(
                    notifId, u, now, "document", fileId, subject, message, dedupKey);
            if (inserted == 1) {
                // 같은 트랜잭션에서 아웃박스에도 적재 (발송 신뢰성)
                mapper.insertOutbox(notifId, u, "WEB", message, now, now);
            }
        }
    }

    /** 파일 구독(이해관계자 등록). 이미 있으면 무시. */
    public void subscribe(String fileId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        mapper.subscribeIgnore(fileId, userId.trim(), Instant.now().getEpochSecond());
    }

    public void unsubscribe(String fileId, String userId) {
        mapper.unsubscribe(fileId, userId);
    }

    public List<String> subscribers(String fileId) {
        return mapper.listSubscribers(fileId);
    }

    // ---------- 조회/읽음 ----------
    public List<Map<String, Object>> listForUser(String user, boolean unreadOnly, int limit) {
        return mapper.listByUser(user, unreadOnly, limit);
    }

    public int unreadCount(String user) {
        return mapper.countUnread(user);
    }

    public boolean markRead(String notificationId, String user) {
        return mapper.markRead(notificationId, user, Instant.now().getEpochSecond()) > 0;
    }

    public List<Map<String, Object>> outbox(int limit) {
        return mapper.listOutbox(limit);
    }
}
