package com.docversion.web;

import com.docversion.service.DocumentLifecycleService;
import com.docversion.service.DocumentLifecycleService.StatusView;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 문서 상태 관리 통신 창구 (RD-SRS-9.6). /api/documents/{id}/status 하위.
 * <p>P1: 예외→HTTP 매핑은 GlobalExceptionHandler(어드바이스)로 일원화했다.
 * (없음→404, 잘못된 상태 문자열→400, 전이 규칙 위반/열린 요청 충돌→409, 권한 없음→403)
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentLifecycleController {

    private final DocumentLifecycleService service;
    private final com.docversion.service.DocumentAccessPolicy access; // 07/19 - P1-②

    public DocumentLifecycleController(DocumentLifecycleService service,
                                       com.docversion.service.DocumentAccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    /** 현재 상태 + 전이 가능한 다음 상태 목록. 07/19 - P1-②: 이해관계자만. */
    @GetMapping("/{fileId}/status")
    public StatusView getStatus(Principal principal, @PathVariable String fileId) {
        access.requireRead(fileId, principal.getName());
        return service.getStatus(fileId);
    }

    /** 상태 변경. 변경자 = 로그인 사용자. */
    @PostMapping("/{fileId}/status")
    public StatusView changeStatus(Principal principal,
                                   @PathVariable String fileId,
                                   @RequestParam String targetStatus,
                                   @RequestParam(required = false) String reason) {
        return service.changeStatus(fileId, principal.getName(), targetStatus, reason);
    }

    /** 상태 변경 이력 (최신순). 07/19 - P1-②: 이해관계자만. */
    @GetMapping("/{fileId}/status-history")
    public List<Map<String, Object>> history(Principal principal, @PathVariable String fileId) {
        access.requireRead(fileId, principal.getName());
        return service.getStatusHistory(fileId);
    }
}
