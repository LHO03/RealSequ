package com.docversion.web;

import com.docversion.service.RetentionPolicyService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 보존 정책 통신 창구 (RD-SRS-9.10). /api/retention 하위.
 * 인증 2-E: 정책 적용의 행위자는 로그인 사용자. (정책 생성/수정의 ADMIN 전용화는 3단계)
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
        try {
            String id = service.createPolicy(scopeType, scopeId, minDays, maxDays, maxVersions, autoCleanup);
            return Map.of("id", id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /** 정책 수정. */
    @PostMapping("/policies/{id}")
    public Map<String, Object> update(@PathVariable String id,
                                      @RequestParam(defaultValue = "0") int minDays,
                                      @RequestParam(defaultValue = "0") int maxDays,
                                      @RequestParam(defaultValue = "0") int maxVersions,
                                      @RequestParam(defaultValue = "true") boolean autoCleanup) {
        try {
            service.updatePolicy(id, minDays, maxDays, maxVersions, autoCleanup);
            return Map.of("ok", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 정책 비활성화(soft delete). */
    @PostMapping("/policies/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable String id) {
        try {
            service.deactivatePolicy(id);
            return Map.of("ok", true);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 정책 즉시 적용(수동 정리). 행위자 = 로그인 사용자. 삭제된 버전 수 반환. */
    @PostMapping("/policies/{id}/apply")
    public Map<String, Object> apply(Principal principal, @PathVariable String id) {
        try {
            int deleted = service.applyPolicy(id, principal.getName());
            return Map.of("deleted", deleted);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }
}
