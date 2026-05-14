package com.example.docver.controller;

import com.example.docver.model.NotificationInfo;
import com.example.docver.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 알림 조회 + 읽음 처리 REST 엔드포인트.
 *
 * 의사코드 getUserNotifications / markNotificationRead / getUnreadCount 매핑.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    /**
     * 사용자별 알림 목록 (최신순).
     * GET /api/notifications?userId=alice&unreadOnly=true&limit=20&offset=0
     */
    @GetMapping
    public ResponseEntity<List<NotificationInfo>> getNotifications(
            @RequestParam String userId,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<NotificationInfo> result = notificationService.getUserNotifications(
                userId, unreadOnly, limit, offset);
        return ResponseEntity.ok(result);
    }

    /**
     * 안 읽은 알림 카운트 (UI 배지용).
     * GET /api/notifications/unread-count?userId=alice
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Long>> getUnreadCount(@RequestParam String userId) {
        long count = notificationService.getUnreadCount(userId);
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    /**
     * 단건 읽음 처리.
     * POST /api/notifications/{notificationId}/read?userId=alice
     */
    @PostMapping("/{notificationId}/read")
    public ResponseEntity<Map<String, Boolean>> markRead(
            @PathVariable String notificationId,
            @RequestParam String userId) {
        boolean affected = notificationService.markNotificationRead(userId, notificationId);
        return ResponseEntity.ok(Map.of("marked", affected));
    }
}
