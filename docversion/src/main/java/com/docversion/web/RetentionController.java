package com.docversion.web;

import com.docversion.service.RetentionPolicyService;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 보존 정책 통신 창구 (RD-SRS-9.10). /api/retention 하위 (ADMIN 전용 — SecurityConfig).
 * <p>P1: 예외→HTTP 매핑은 GlobalExceptionHandler로 일원화(값 검증 실패→400, 정책 없음→404).
 */
@RestController
@RequestMapping("/api/retention")
public class RetentionController {

    private final RetentionPolicyService service;

    public RetentionController(RetentionPolicyService service) {
        this.service = service;
    }

    /** 정책 목록(activeOnly=true면 활성만). */
    @GetMapping("/policies")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return service.listPolicies(activeOnly);
    }

    /** 정책 생성. */
    @PostMapping("/policies")
    public Map<String, Object> create(@RequestParam String scopeType,
                                      @RequestParam(required = false) String scopeId,
                                      @RequestParam(defaultValue = "0") int minDays,
                                      @RequestParam(defaultValue = "0") int maxDays,
                                      @RequestParam(defaultValue = "0") int maxVersions,
                                      @RequestParam(defaultValue = "true") boolean autoCleanup) {
        String id = service.createPolicy(scopeType, scopeId, minDays, maxDays, maxVersions, autoCleanup);
        return Map.of("id", id);
    }

    /** 정책 수정. */
    @PostMapping("/policies/{id}")
    public Map<String, Object> update(@PathVariable String id,
                                      @RequestParam(defaultValue = "0") int minDays,
                                      @RequestParam(defaultValue = "0") int maxDays,
                                      @RequestParam(defaultValue = "0") int maxVersions,
                                      @RequestParam(defaultValue = "true") boolean autoCleanup) {
        service.updatePolicy(id, minDays, maxDays, maxVersions, autoCleanup);
        return Map.of("ok", true);
    }

    /** 정책 비활성화(soft delete). */
    @PostMapping("/policies/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable String id) {
        service.deactivatePolicy(id);
        return Map.of("ok", true);
    }

    /** 정책 즉시 적용(수동 정리). 행위자 = 로그인 사용자. 삭제된 버전 수 반환. */
    @PostMapping("/policies/{id}/apply")
    public Map<String, Object> apply(Principal principal, @PathVariable String id) {
        int deleted = service.applyPolicy(id, principal.getName());
        return Map.of("deleted", deleted);
    }
}
