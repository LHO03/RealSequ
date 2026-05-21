package com.example.docver.controller;

import com.example.docver.dto.DemoRequests.CreateVersionRequest;
import com.example.docver.dto.DemoRequests.DecideApprovalRequest;
import com.example.docver.dto.DemoRequests.RequestApprovalRequest;
import com.example.docver.dto.DemoRequests.UpdateDocumentRequest;
import com.example.docver.model.ApprovalProgress;
import com.example.docver.model.VersionInfo;
import com.example.docver.service.DocumentVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 문서 버전 + 승인 워크플로우 REST 엔드포인트.
 *
 * 시연 시나리오 1 전체 경로 노출.
 *
 * 엔드포인트:
 *   POST   /api/versions/initial      - 초기 버전 생성 (createInitialVersion)
 *   POST   /api/versions/update       - 문서 수정 (onDocumentModified)
 *   POST   /api/approvals/request     - 승인 요청 (REQUEST)
 *   POST   /api/approvals/decide      - 승인 결정 (APPROVE/REJECT)
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DocumentVersionController {

    private final DocumentVersionService service;

    @PostMapping("/versions/initial")
    public ResponseEntity<VersionInfo> createInitial(@Valid @RequestBody CreateVersionRequest req) {
        VersionInfo result = service.createInitialVersion(
                req.getUserId(), req.getFileId(), req.getSize(), req.getMimeType()
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/versions/update")
    public ResponseEntity<VersionInfo> updateDocument(@Valid @RequestBody UpdateDocumentRequest req) {
        VersionInfo result = service.updateDocument(
                req.getUserId(), req.getFileId(), req.getSize(), req.getMimeType()
        );
        return ResponseEntity.ok(result);
    }

    @PostMapping("/approvals/request")
    public ResponseEntity<Map<String, Object>> requestApproval(
            @Valid @RequestBody RequestApprovalRequest req) {
        String ruleId = service.requestApproval(
                req.getUserId(), req.getFileId(), req.getComment(), req.getApprovers(),
                req.getConsensusMode(), req.getRequiredApprovals()
        );
        return ResponseEntity.ok(Map.of(
                "ruleId", ruleId,
                "status", "UNDER_REVIEW",
                "consensusMode", req.getConsensusMode(),
                "requiredApprovals", req.getRequiredApprovals()
        ));
    }

    @PostMapping("/approvals/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @Valid @RequestBody DecideApprovalRequest req) {
        boolean success = service.decideApproval(
                req.getUserId(), req.getFileId(), req.getAction(), req.getComment()
        );
        // 05/15: 합의가 PENDING이면 success=true이지만 finalStatus는 UNDER_REVIEW 유지
        // 호출자는 GET /api/files/{fileId}/status로 실제 상태를 다시 확인
        return ResponseEntity.ok(Map.of(
                "success", success,
                "action", req.getAction().name()
        ));
    }

    /**
     * 05/15: 승인 요청 취소 (시나리오 4).
     * 의사코드 cancelApprovalRequest 매핑.
     *
     * POST /api/approvals/cancel
     */
    @PostMapping("/approvals/cancel")
    public ResponseEntity<Map<String, Object>> cancelApproval(
            @Valid @RequestBody com.example.docver.dto.DemoRequests.CancelApprovalRequest req) {
        boolean success = service.cancelApprovalRequest(
                req.getUserId(), req.getFileId(), req.getComment()
        );
        return ResponseEntity.ok(Map.of(
                "success", success,
                "action", "CANCEL"
        ));
    }

    // ============================================================
    // Group A: 조회 API (05/15 추가)
    // ============================================================

    /**
     * 파일의 현재 상태 조회.
     * 의사코드 getCurrentStatusTag 매핑.
     *
     * GET /api/files/{fileId}/status
     * 응답: { "fileId": "...", "status": "draft" | "under_review" | ... | "" }
     */
    @GetMapping("/files/{fileId}/status")
    public ResponseEntity<Map<String, String>> getFileStatus(@PathVariable String fileId) {
        String status = service.getFileStatus(fileId);
        return ResponseEntity.ok(Map.of(
                "fileId", fileId,
                "status", status  // 빈 문자열은 "상태 미지정"
        ));
    }

    /**
     * 파일의 버전 목록 조회.
     * 의사코드 getVersionsAtTime + countVersions 매핑.
     *
     * GET /api/files/{fileId}/versions?limit=50&offset=0
     * 응답: { "versions": [...], "total": N, "limit": ..., "offset": ... }
     */
    @GetMapping("/files/{fileId}/versions")
    public ResponseEntity<Map<String, Object>> getVersions(
            @PathVariable String fileId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        Map<String, Object> result = service.getVersions(fileId, limit, offset);
        return ResponseEntity.ok(result);
    }

    /**
     * 파일의 승인 진행 상황 조회.
     * 의사코드 getApprovalProgress 매핑.
     *
     * GET /api/files/{fileId}/approval-progress
     * 응답: ApprovalProgress 구조 (rule, counters, decisions, pending)
     */
    @GetMapping("/files/{fileId}/approval-progress")
    public ResponseEntity<ApprovalProgress> getApprovalProgress(
            @PathVariable String fileId) {
        return ResponseEntity.ok(service.getApprovalProgress(fileId));
    }
}
