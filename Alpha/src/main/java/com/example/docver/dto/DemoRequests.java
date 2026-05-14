package com.example.docver.dto;

import com.example.docver.model.ApprovalAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * REST API 요청 DTO 모음.
 * 한 파일에 모아둠 (시연 단순화 - 운영 코드라면 분리 권장).
 */
public class DemoRequests {

    @Data
    public static class CreateVersionRequest {
        @NotBlank private String userId;
        @NotBlank private String fileId;
        private long size = 1024;
        private String mimeType = "text/plain";
    }

    @Data
    public static class UpdateDocumentRequest {
        @NotBlank private String userId;
        @NotBlank private String fileId;
        private long size = 2048;
        private String mimeType = "text/plain";
    }

    @Data
    public static class RequestApprovalRequest {
        @NotBlank private String userId;
        @NotBlank private String fileId;
        private String comment = "";
        @NotEmpty private List<String> approvers;
    }

    @Data
    public static class DecideApprovalRequest {
        @NotBlank private String userId;
        @NotBlank private String fileId;
        @NotNull private ApprovalAction action;
        private String comment = "";
    }
}
