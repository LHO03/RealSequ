package com.docversion.web;

import com.docversion.service.ApprovalService;
import com.docversion.service.ApprovalService.ApprovalState;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 승인 워크플로 통신 창구 (RD-SRS-9.7). /api/documents/{id}/approval 하위.
 */
@RestController
@RequestMapping("/api/documents")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /** 현재 열린 요청 + 요청 이력. */
    @GetMapping("/{fileId}/approval")
    public ApprovalState get(@PathVariable String fileId) {
        return service.getState(fileId);
    }

    /** 승인 요청 생성. */
    @PostMapping("/{fileId}/approval/request")
    public ApprovalState request(@PathVariable String fileId,
                                 @RequestParam String requesterId,
                                 @RequestParam String approverId,
                                 @RequestParam(required = false) String comment) {
        return run(() -> service.request(fileId, requesterId, approverId, comment));
    }

    /** 승인. */
    @PostMapping("/{fileId}/approval/approve")
    public ApprovalState approve(@PathVariable String fileId,
                                 @RequestParam String actorId,
                                 @RequestParam(required = false) String comment) {
        return run(() -> service.approve(fileId, actorId, comment));
    }

    /** 반려. */
    @PostMapping("/{fileId}/approval/reject")
    public ApprovalState reject(@PathVariable String fileId,
                                @RequestParam String actorId,
                                @RequestParam(required = false) String comment) {
        return run(() -> service.reject(fileId, actorId, comment));
    }

    /** 요청 취소(철회). */
    @PostMapping("/{fileId}/approval/cancel")
    public ApprovalState cancel(@PathVariable String fileId,
                                @RequestParam String actorId,
                                @RequestParam(required = false) String comment) {
        return run(() -> service.cancel(fileId, actorId, comment));
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
