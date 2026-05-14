package com.example.docver.controller;

import com.example.docver.IntegrationTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * REST API 레이어 통합 테스트.
 *
 * Scenario1IntegrationTest가 서비스 레이어를 직접 호출했다면,
 * 여기는 HTTP 경로(컨트롤러 → DTO 검증 → 서비스 → DB)를 끝까지 검증.
 *
 * Postman 컬렉션과 1:1로 매칭되는 케이스 우선.
 * 박사님이 시연 중 "그 API가 실제로 응답 형식이 어떻게 되나"를 물을 때 답할 수 있도록.
 */
@AutoConfigureMockMvc
@DisplayName("REST API 통합 테스트")
class ApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @Test
    @DisplayName("POST /api/versions/initial - 정상 응답 구조")
    void createInitial_returnsVersionInfo() throws Exception {
        Map<String, Object> req = Map.of(
                "userId", ALICE,
                "fileId", "api_test_1",
                "size", 1024,
                "mimeType", "text/plain"
        );

        mockMvc.perform(post("/api/versions/initial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").exists())
                .andExpect(jsonPath("$.fileId").value("api_test_1"))
                .andExpect(jsonPath("$.userId").value(ALICE))
                .andExpect(jsonPath("$.size").value(1024));
    }

    @Test
    @DisplayName("POST /api/approvals/request - 응답에 ruleId 포함")
    void requestApproval_returnsRuleId() throws Exception {
        // 사전: alice가 파일 생성
        createFile(ALICE, "api_test_2");

        Map<String, Object> req = Map.of(
                "userId", ALICE,
                "fileId", "api_test_2",
                "comment", "검토 부탁",
                "approvers", List.of(BOB)
        );

        mockMvc.perform(post("/api/approvals/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").exists())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    @DisplayName("POST /api/approvals/decide - APPROVE 시 finalStatus=APPROVED")
    void decideApproval_returnsFinalStatus() throws Exception {
        createFile(ALICE, "api_test_3");
        requestApprovalAsAlice("api_test_3");

        Map<String, Object> req = Map.of(
                "userId", BOB,
                "fileId", "api_test_3",
                "action", "APPROVE",
                "comment", "OK"
        );

        mockMvc.perform(post("/api/approvals/decide")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.finalStatus").value("APPROVED"));
    }

    @Test
    @DisplayName("GET /api/notifications - 시간 역순으로 반환")
    void getNotifications_returnsListInDescOrder() throws Exception {
        createFile(ALICE, "api_test_4_a");
        createFile(ALICE, "api_test_4_b");  // 더 최근

        MvcResult result = mockMvc.perform(get("/api/notifications")
                        .param("userId", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andReturn();

        // 응답을 파싱해서 순서 확인
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notifs = objectMapper.readValue(
                result.getResponse().getContentAsString(), List.class);
        long ts0 = ((Number) notifs.get(0).get("timestamp")).longValue();
        long ts1 = ((Number) notifs.get(1).get("timestamp")).longValue();
        assertThat(ts0).isGreaterThanOrEqualTo(ts1);  // DESC
    }

    @Test
    @DisplayName("GET /api/notifications/unread-count - 카운트 정상")
    void getUnreadCount_returnsCount() throws Exception {
        createFile(ALICE, "api_test_5");

        mockMvc.perform(get("/api/notifications/unread-count")
                        .param("userId", ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    @DisplayName("400 응답: 유효성 검증 실패 (userId 누락)")
    void validation_missingUserId_returns400() throws Exception {
        Map<String, Object> req = Map.of(
                "fileId", "api_test_6",
                "size", 1024
        );

        mockMvc.perform(post("/api/versions/initial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation"));
    }

    @Test
    @DisplayName("400 응답: 비즈니스 규칙 위반 (중복 승인 요청)")
    void workflow_duplicateRequest_returns400() throws Exception {
        createFile(ALICE, "api_test_7");
        requestApprovalAsAlice("api_test_7");

        // 같은 파일에 또 요청 — WorkflowException
        Map<String, Object> req = Map.of(
                "userId", ALICE,
                "fileId", "api_test_7",
                "comment", "재요청",
                "approvers", List.of(BOB)
        );

        mockMvc.perform(post("/api/approvals/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("workflow"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("already pending")));
    }

    // ============================================================
    // 헬퍼: 테스트 사전 조건 셋업
    // ============================================================

    private void createFile(String userId, String fileId) throws Exception {
        Map<String, Object> req = Map.of(
                "userId", userId,
                "fileId", fileId,
                "size", 1024,
                "mimeType", "text/plain"
        );
        mockMvc.perform(post("/api/versions/initial")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    private void requestApprovalAsAlice(String fileId) throws Exception {
        Map<String, Object> req = Map.of(
                "userId", ALICE,
                "fileId", fileId,
                "comment", "",
                "approvers", List.of(BOB)
        );
        mockMvc.perform(post("/api/approvals/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }
}
