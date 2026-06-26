package com.docversion.web;

import com.docversion.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 알림 통신 창구 (RD-SRS-9.9).
 * 인증 2-E: "내 알림"(조회·읽음)과 구독(이 문서를 내가 구독)은 로그인 사용자 기준으로 동작한다.
 */
@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    /** 내 알림 목록(unreadOnly=true면 안 읽은 것만). 대상 = 로그인 사용자. */
    @GetMapping("/notifications")
    public Map<String, Object> list(Principal principal,
                                    @RequestParam(defaultValue = "false") boolean unreadOnly,
                                    @RequestParam(defaultValue = "50") int limit) {
        String userId = principal.getName();
        List<Map<String, Object>> items = service.listForUser(userId, unreadOnly, limit);
        return Map.of("unread", service.unreadCount(userId), "items", items);
    }

    /** 내 알림 읽음 처리. 대상 = 로그인 사용자. */
    @PostMapping("/notifications/{notificationId}/read")
    public Map<String, Object> read(Principal principal, @PathVariable String notificationId) {
        String userId = principal.getName();
        boolean ok = service.markRead(notificationId, userId);
        if (!ok) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "읽을 알림을 찾지 못했거나 이미 읽었습니다.");
        }
        return Map.of("ok", true, "unread", service.unreadCount(userId));
    }

    /** 아웃박스 상태(시연용: PENDING/SENT/DLQ 확인). (읽기 — 3단계에서 ADMIN 전용 예정) */
    @GetMapping("/notifications/outbox")
    public List<Map<String, Object>> outbox(@RequestParam(defaultValue = "50") int limit) {
        return service.outbox(limit);
    }

    /** 이 문서를 구독(내가 이해관계자로 등록). 대상 = 로그인 사용자. */
    @PostMapping("/documents/{fileId}/subscribe")
    public Map<String, Object> subscribe(Principal principal, @PathVariable String fileId) {
        service.subscribe(fileId, principal.getName());
        return Map.of("subscribers", service.subscribers(fileId));
    }

    /** 이 문서 구독 해제(나를 제거). 대상 = 로그인 사용자. */
    @PostMapping("/documents/{fileId}/unsubscribe")
    public Map<String, Object> unsubscribe(Principal principal, @PathVariable String fileId) {
        service.unsubscribe(fileId, principal.getName());
        return Map.of("subscribers", service.subscribers(fileId));
    }

    /** 파일 구독자 목록. (읽기 — 비로그인 허용) */
    @GetMapping("/documents/{fileId}/subscribers")
    public List<String> subscribers(@PathVariable String fileId) {
        return service.subscribers(fileId);
    }
}
