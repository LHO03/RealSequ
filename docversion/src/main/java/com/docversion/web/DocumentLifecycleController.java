package com.docversion.web;

import com.docversion.service.DocumentLifecycleService;
import com.docversion.service.DocumentLifecycleService.StatusView;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 문서 상태 관리 통신 창구 (RD-SRS-9.6).
 * 기존 DocumentVersionController와 같은 /api/documents 경로를 공유하되,
 * 상태 전용 하위 경로(/status, /status-history)만 담당한다.
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

    /** 현재 상태 + 전이 가능한 다음 상태 목록 조회. 07/19 - P1-②: 이해관계자만. */
    @GetMapping("/{fileId}/status")
    public StatusView getStatus(java.security.Principal principal, @PathVariable String fileId) {
        try {
            access.requireRead(fileId, principal.getName());
            return service.getStatus(fileId);
        } catch (com.docversion.service.ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 상태 변경. 변경자 = 로그인 사용자. 허용되지 않은 전이는 409로 거부하고 사유를 메시지로 돌려준다. */
    @PostMapping("/{fileId}/status")
    public StatusView changeStatus(Principal principal,
                                   @PathVariable String fileId,
                                   @RequestParam String targetStatus,
                                   @RequestParam(required = false) String reason) {
        try {
            return service.changeStatus(fileId, principal.getName(), targetStatus, reason);
        } catch (com.docversion.service.ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    /** 상태 변경 이력 (최신순). 07/19 - P1-②: 이해관계자만. */
    @GetMapping("/{fileId}/status-history")
    public List<Map<String, Object>> history(java.security.Principal principal, @PathVariable String fileId) {
        try {
            access.requireRead(fileId, principal.getName());
        } catch (com.docversion.service.ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return service.getStatusHistory(fileId);
    }
}
