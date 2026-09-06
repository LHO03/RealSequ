package com.docversion.web;

import com.docversion.service.DelegationService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * 승인 위임 (RD-SRS-9.7 확장, 4-C) — 사용자 단위 자원.
 * 모든 창구는 로그인 필수, 행위자 = 세션 신원.
 * <p>P1: 예외→HTTP 매핑은 GlobalExceptionHandler로 일원화(잘못된 입력→400, 상태 충돌→409).
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
        return service.delegate(principal.getName(), delegateId, days);
    }

    /** 내 활성 위임 해지. */
    @PostMapping("/revoke")
    public Map<String, Object> revoke(Principal principal) {
        return service.revoke(principal.getName());
    }
}
