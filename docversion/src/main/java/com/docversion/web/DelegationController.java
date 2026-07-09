package com.docversion.web;

import com.docversion.service.DelegationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Map;

/**
 * 승인 위임 (RD-SRS-9.7 확장, 4-C) — 사용자 단위 자원.
 * 문서 단위(/api/documents/...)가 아니라 "나의 위임"이므로 별도 경로를 쓴다.
 * 모든 창구는 로그인 필수 (SecurityConfig에서 잠금), 행위자 = 세션 신원.
 */
@RestController
@RequestMapping("/api/approval/delegation")
public class DelegationController {

    private final DelegationService service;

    public DelegationController(DelegationService service) {
        this.service = service;
    }

    /** 내 위임 현황: mine(내가 건 것) + received(내게 온 것). */
    @GetMapping
    public Map<String, Object> state(Principal principal) {
        return service.state(principal.getName());
    }

    /** 위임 설정: 지금부터 days일 동안 delegateId에게. */
    @PostMapping
    public Map<String, Object> delegate(Principal principal,
                                        @RequestParam String delegateId,
                                        @RequestParam(defaultValue = "7") int days) {
        return run(() -> service.delegate(principal.getName(), delegateId, days));
    }

    /** 내 활성 위임 해지. */
    @PostMapping("/revoke")
    public Map<String, Object> revoke(Principal principal) {
        return run(() -> service.revoke(principal.getName()));
    }

    private Map<String, Object> run(java.util.function.Supplier<Map<String, Object>> op) {
        try {
            return op.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
