package com.docversion.web;

import com.docversion.service.ApprovalService;
import com.docversion.service.ApprovalService.ApprovalState;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * 승인 워크플로 통신 창구 (RD-SRS-9.7). /api/documents/{id}/approval 하위.
 * 인증 2-D: 요청자·처리자는 로그인 사용자로 결정한다.
 * <p>P1: 예외→HTTP 매핑은 GlobalExceptionHandler로 일원화했다.
 * (없음→404, 승인자 0명·잘못된 mode 등 입력 오류→400, 상태 충돌→409, 권한 없음→403)
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
    public ApprovalState get(Principal principal, @PathVariable String fileId) {
        access.requireRead(fileId, principal.getName());
        return service.getState(fileId);
    }

    /** 승인 요청 생성 (V8 다중). 요청자 = 로그인 사용자.
     *  approvers: 쉼표로 구분한 승인자 ID 목록. mode: ALL/MAJORITY/SEQUENTIAL. */
    @PostMapping("/{fileId}/approval/request")
    public ApprovalState request(Principal principal,
                                 @PathVariable String fileId,
                                 @RequestParam String approvers,
                                 @RequestParam(defaultValue = "ALL") String mode,
                                 @RequestParam(required = false) String comment) {
        java.util.List<String> list = java.util.Arrays.asList(approvers.split(","));
        return service.request(fileId, principal.getName(), list, mode, comment);
    }

    /** 승인. 처리자 = 로그인 사용자. */
    @PostMapping("/{fileId}/approval/approve")
    public ApprovalState approve(Principal principal, @PathVariable String fileId,
                                 @RequestParam(required = false) String comment) {
        return service.approve(fileId, principal.getName(), comment);
    }

    /** 반려. 처리자 = 로그인 사용자. */
    @PostMapping("/{fileId}/approval/reject")
    public ApprovalState reject(Principal principal, @PathVariable String fileId,
                                @RequestParam(required = false) String comment) {
        return service.reject(fileId, principal.getName(), comment);
    }

    /** 요청 취소(철회). 처리자 = 로그인 사용자. */
    @PostMapping("/{fileId}/approval/cancel")
    public ApprovalState cancel(Principal principal, @PathVariable String fileId,
                                @RequestParam(required = false) String comment) {
        return service.cancel(fileId, principal.getName(), comment);
    }

    /** 판정 번복 (4-D): 열린 요청에서 내 판정을 PENDING으로 되돌림. 확정 후 불가. */
    @PostMapping("/{fileId}/approval/retract")
    public ApprovalState retract(Principal principal, @PathVariable String fileId,
                                 @RequestParam(required = false) String comment) {
        return service.retract(fileId, principal.getName(), comment);
    }
}
