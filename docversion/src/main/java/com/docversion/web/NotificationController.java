package com.docversion.web;

import com.docversion.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 알림 통신 창구 (RD-SRS-9.9).
 */
@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** 사용자별 알림 목록(unreadOnly=true면 안 읽은 것만). */
    @GetMapping("/notifications")
    public Map<String, Object> list(@RequestParam String userId,
                                    @RequestParam(defaultValue = "false") boolean unreadOnly,
                                    @RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> items = service.listForUser(userId, unreadOnly, limit);
        return Map.of("unread", service.unreadCount(userId), "items", items);
    }

    /** 알림 읽음 처리(본인 것만). */
    @PostMapping("/notifications/{notificationId}/read")
    public Map<String, Object> read(@PathVariable String notificationId, @RequestParam String userId) {
        boolean ok = service.markRead(notificationId, userId);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "읽을 알림을 찾지 못했거나 이미 읽었습니다.");
        }
        return Map.of("ok", true, "unread", service.unreadCount(userId));
    }

    /** 아웃박스 상태(시연용: PENDING/SENT/DLQ 확인). */
    @GetMapping("/notifications/outbox")
    public List<Map<String, Object>> outbox(@RequestParam(defaultValue = "50") int limit) {
        return service.outbox(limit);
    }

    /** 파일 구독(이해관계자 등록). */
    @PostMapping("/documents/{fileId}/subscribe")
    public Map<String, Object> subscribe(@PathVariable String fileId, @RequestParam String userId) {
        service.subscribe(fileId, userId);
        return Map.of("subscribers", service.subscribers(fileId));
    }

    /** 파일 구독 해제. */
    @PostMapping("/documents/{fileId}/unsubscribe")
    public Map<String, Object> unsubscribe(@PathVariable String fileId, @RequestParam String userId) {
        service.unsubscribe(fileId, userId);
        return Map.of("subscribers", service.subscribers(fileId));
    }

    /** 파일 구독자 목록. */
    @GetMapping("/documents/{fileId}/subscribers")
    public List<String> subscribers(@PathVariable String fileId) {
        return service.subscribers(fileId);
    }
}
