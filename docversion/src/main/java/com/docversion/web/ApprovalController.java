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
 * 승인자 목록(approvers, CSV)과 판정 방식(mode)은 "누구에게 어떻게 맡길지"의 입력이므로 그대로 받는다.
 */
@RestController
@RequestMapping("/api/documents")
public class ApprovalController {

    private final ApprovalService service;
    private final com.docversion.service.DocumentAccessPolicy access; // 07/19 - P1-②

    public ApprovalController(ApprovalService service,
                              com.docversion.service.DocumentAccessPolicy access) {
        this.service = service;
        this.access = access;
    }

    /** 현재 열린 요청 + 요청 이력. 07/19 - P1-②: 이해관계자만. */
    @GetMapping("/{fileId}/approval")
    public ApprovalState get(java.security.Principal principal, @PathVariable String fileId) {
        return run(() -> {
            access.requireRead(fileId, principal.getName());
            return service.getState(fileId);
        });
    }

    /** 승인 요청 생성 (V8 다중). 요청자 = 로그인 사용자.
     *  approvers: 쉼표로 구분한 승인자 ID 목록. mode: ALL(전원)/MAJORITY(과반). */
    @PostMapping("/{fileId}/approval/request")
    public ApprovalState request(Principal principal,
                                 @PathVariable String fileId,
                                 @RequestParam String approvers,
                                 @RequestParam(defaultValue = "ALL") String mode,
                                 @RequestParam(required = false) String comment) {
        java.util.List<String> list = java.util.Arrays.asList(approvers.split(","));
        return run(() -> service.request(fileId, principal.getName(), list, mode, comment));
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

    /** 판정 번복 (4-D): 열린 요청에서 내 판정을 PENDING으로 되돌림. 확정 후 불가. */
    @PostMapping("/{fileId}/approval/retract")
    public ApprovalState retract(Principal principal,
                                 @PathVariable String fileId,
                                 @RequestParam(required = false) String comment) {
        return run(() -> service.retract(fileId, principal.getName(), comment));
    }

    // 예외 → HTTP 상태 변환 (사유 메시지는 응답 본문에 포함)
    private ApprovalState run(java.util.function.Supplier<ApprovalState> op) {
        try {
            return op.get();
        } catch (com.docversion.service.ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
