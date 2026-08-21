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
 * 인증 2-E: "내 알림"(조회·읽음)과 구독은 로그인 사용자 기준으로 동작한다.
 * <p>P1: 예외→HTTP 매핑은 GlobalExceptionHandler로 일원화(없음→404, 권한 없음→403).
 * (read의 404는 예외가 아니라 "이미 읽음/없음" 반환값 기반 조건이므로 여기서 직접 처리한다.)
 */
@RestController
@RequestMapping("/api")
public class NotificationController {

    private final NotificationService service;
    private final com.docversion.service.DocumentAccessPolicy access; // 07/19 - P1-②

    public NotificationController(NotificationService service,
                                  com.docversion.service.DocumentAccessPolicy access) {
        this.service = service;
        this.access = access;
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

    /** 아웃박스 상태(시연용). (읽기 — ADMIN 전용, SecurityConfig) */
    @GetMapping("/notifications/outbox")
    public List<Map<String, Object>> outbox(@RequestParam(defaultValue = "50") int limit) {
        return service.outbox(limit);
    }

    /** 이 문서를 구독. 07/19 - P1-①: 소유자만 가능. */
    @PostMapping("/documents/{fileId}/subscribe")
    public Map<String, Object> subscribe(Principal principal, @PathVariable String fileId) {
        service.subscribeChecked(fileId, principal.getName());
        return Map.of("subscribers", service.subscribers(fileId));
    }

    /** 이 문서 구독 해제(나를 제거). 대상 = 로그인 사용자. */
    @PostMapping("/documents/{fileId}/unsubscribe")
    public Map<String, Object> unsubscribe(Principal principal, @PathVariable String fileId) {
        service.unsubscribe(fileId, principal.getName());
        return Map.of("subscribers", service.subscribers(fileId));
    }

    /** 파일 구독자 목록. 07/19 - P1-②: 이해관계자만. */
    @GetMapping("/documents/{fileId}/subscribers")
    public List<String> subscribers(Principal principal, @PathVariable String fileId) {
        access.requireRead(fileId, principal.getName());
        return service.subscribers(fileId);
    }
}
