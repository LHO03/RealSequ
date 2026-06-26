package com.docversion.web;

import com.docversion.service.ApprovalService;
import com.docversion.service.ApprovalService.ApprovalState;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;

/**
 * 승인 워크플로 통신 창구 (RD-SRS-9.7). /api/documents/{id}/approval 하위.
 * 인증 2-D: 요청자·처리자(승인/반려/취소)는 로그인 사용자로 결정한다.
 * 승인자 지정(approverId)은 "누구에게 맡길지"를 고르는 입력이므로 그대로 받는다.
 */
@RestController
@RequestMapping("/api/documents")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /** 현재 열린 요청 + 요청 이력. (읽기 — 비로그인 허용) */
    @GetMapping("/{fileId}/approval")
    public ApprovalState get(@PathVariable String fileId) {
        return service.getState(fileId);
    }

    /** 승인 요청 생성. 요청자 = 로그인 사용자. */
    @PostMapping("/{fileId}/approval/request")
    public ApprovalState request(Principal principal,
                                 @PathVariable String fileId,
                                 @RequestParam String approverId,
                                 @RequestParam(required = false) String comment) {
        return run(() -> service.request(fileId, principal.getName(), approverId, comment));
    }

    /** 승인. 처리자 = 로그인 사용자. */
    @PostMapping("/{fileId}/approval/approve")
    public ApprovalState approve(Principal principal,
                                 @PathVariable String fileId,
                                 @RequestParam(required = false) String comment) {
        return run(() -> service.approve(fileId, principal.getName(), comment));
    }

    /** 반려. 처리자 = 로그인 사용자. */
    @PostMapping("/{fileId}/approval/reject")
    public ApprovalState reject(Principal principal,
                                @PathVariable String fileId,
                                @RequestParam(required = false) String comment) {
        return run(() -> service.reject(fileId, principal.getName(), comment));
    }

    /** 요청 취소(철회). 처리자 = 로그인 사용자. */
    @PostMapping("/{fileId}/approval/cancel")
    public ApprovalState cancel(Principal principal,
                                @PathVariable String fileId,
                                @RequestParam(required = false) String comment) {
        return run(() -> service.cancel(fileId, principal.getName(), comment));
    }

    // 예외 → HTTP 상태 변환 (사유 메시지는 응답 본문에 포함)
    private ApprovalState run(java.util.function.Supplier<ApprovalState> op) {
        try {
            return op.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
