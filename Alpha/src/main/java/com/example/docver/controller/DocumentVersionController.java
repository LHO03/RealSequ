package com.example.docver.controller;

import com.example.docver.dto.DemoRequests.CreateVersionRequest;
import com.example.docver.dto.DemoRequests.DecideApprovalRequest;
import com.example.docver.dto.DemoRequests.RequestApprovalRequest;
import com.example.docver.dto.DemoRequests.UpdateDocumentRequest;
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
    public ResponseEntity<Map<String, String>> requestApproval(
            @Valid @RequestBody RequestApprovalRequest req) {
        String ruleId = service.requestApproval(
                req.getUserId(), req.getFileId(), req.getComment(), req.getApprovers()
        );
        return ResponseEntity.ok(Map.of("ruleId", ruleId, "status", "UNDER_REVIEW"));
    }

    @PostMapping("/approvals/decide")
    public ResponseEntity<Map<String, Object>> decideApproval(
            @Valid @RequestBody DecideApprovalRequest req) {
        boolean success = service.decideApproval(
                req.getUserId(), req.getFileId(), req.getAction(), req.getComment()
        );
        String finalStatus = req.getAction().name().equals("APPROVE") ? "APPROVED" : "REJECTED";
        return ResponseEntity.ok(Map.of(
                "success", success,
                "action", req.getAction().name(),
                "finalStatus", finalStatus
        ));
    }
}
