package com.example.docver.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 승인 워크플로우 4개 테이블 접근:
 *   - approval_rules
 *   - approval_rule_requesters
 *   - approval_rule_approvers
 *   - approval_activity
 *
 * 의사코드의 processApprovalWorkflow / processApprovalDecision의
 * DB 호출 매핑.
 */
@Repository
@RequiredArgsConstructor
public class ApprovalRepository {

    private final JdbcTemplate jdbc;

    /**
     * pending 태그가 이미 할당된 OPEN 규칙이 있는지 확인 (중복 요청 방어).
     * 의사코드 processApprovalWorkflow REQUEST의 line 895-905.
     */
    public boolean hasOpenPendingForFile(String fileId, String pendingTag) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM systemtag_object_mapping " +
                "WHERE objectid = ? AND objecttype = 'files' " +
                "AND systemtagid IN (SELECT id FROM systemtag WHERE name = ?)",
                Integer.class, fileId, pendingTag
        );
        return count != null && count > 0;
    }

    /**
     * 새 승인 규칙 INSERT.
     * 시연 단순화: consensus_mode='THRESHOLD', required_approvals=1 고정.
     */
    public void insertRule(String ruleId, String fileId,
                           String tagPending, String tagApproved, String tagRejected) {
        jdbc.update(
                "INSERT INTO approval_rules " +
                "(id, file_id, tag_pending, tag_approved, tag_rejected, status, " +
                " consensus_mode, required_approvals, received_approvals, received_rejections) " +
                "VALUES (?, ?, ?, ?, ?, 'OPEN', 'THRESHOLD', 1, 0, 0)",
                ruleId, fileId, tagPending, tagApproved, tagRejected
        );
    }

    public void insertRequester(String ruleId, String userId) {
        jdbc.update(
                "INSERT INTO approval_rule_requesters (rule_id, entity_type, entity_id) " +
                "VALUES (?, 'user', ?)",
                ruleId, userId
        );
    }

    /**
     * 승인자 등록. 시연 단순화: sequence_order=0 (SEQUENTIAL 미사용).
     * 의사코드의 dedup 로직은 호출자가 보장한다고 가정 (시연용).
     */
    public void insertApprover(String ruleId, String userId) {
        jdbc.update(
                "INSERT IGNORE INTO approval_rule_approvers " +
                "(rule_id, entity_type, entity_id, sequence_order) " +
                "VALUES (?, 'user', ?, 0)",
                ruleId, userId
        );
    }

    /**
     * OPEN 상태인 규칙 중 사용자가 승인자로 등록된 규칙 ID 조회.
     * 의사코드 processApprovalDecision의 line 2938-2943.
     */
    public Optional<String> findOpenRuleIdForApprover(String fileId, String userId, String pendingTag) {
        List<String> result = jdbc.queryForList(
                "SELECT rule_id FROM approval_rule_approvers " +
                "WHERE entity_id = ? AND rule_id IN " +
                "(SELECT id FROM approval_rules WHERE tag_pending = ? AND file_id = ? AND status = 'OPEN')",
                String.class, userId, pendingTag, fileId
        );
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /**
     * 같은 사용자가 이미 결정했는지 확인.
     * 의사코드 line 2979-2989의 재결정 차단.
     */
    public boolean hasPriorDecision(String ruleId, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_activity " +
                "WHERE rule_id = ? AND user_id = ? AND action IN ('approved', 'rejected')",
                Integer.class, ruleId, userId
        );
        return count != null && count > 0;
    }

    /**
     * 승인 결정 이력 기록.
     */
    public void insertActivity(String ruleId, String userId, String action,
                               long timestamp, String comment) {
        jdbc.update(
                "INSERT INTO approval_activity (rule_id, user_id, action, `timestamp`, comment) " +
                "VALUES (?, ?, ?, ?, ?)",
                ruleId, userId, action, timestamp, comment
        );
    }

    /**
     * 카운터 증가 (received_approvals 또는 received_rejections).
     * isApprove=true → approvals, false → rejections.
     */
    public void incrementCounter(String ruleId, boolean isApprove) {
        String column = isApprove ? "received_approvals" : "received_rejections";
        jdbc.update(
                "UPDATE approval_rules SET " + column + " = " + column + " + 1 WHERE id = ?",
                ruleId
        );
    }

    /**
     * 합의 평가용 카운터 조회.
     * THRESHOLD 모드 + required=1이면 카운터 1개로 즉시 판정 가능 (시연 단순화).
     */
    public Map<String, Object> findCountersByRuleId(String ruleId) {
        return jdbc.queryForMap(
                "SELECT consensus_mode, required_approvals, received_approvals, received_rejections " +
                "FROM approval_rules WHERE id = ?",
                ruleId
        );
    }

    /**
     * 규칙 종료 (OPEN → CLOSED).
     */
    public void closeRule(String ruleId) {
        jdbc.update(
                "UPDATE approval_rules SET status = 'CLOSED' WHERE id = ?",
                ruleId
        );
    }

    /**
     * 규칙의 요청자 ID 조회.
     */
    public Optional<String> findRequesterByRuleId(String ruleId) {
        List<String> result = jdbc.queryForList(
                "SELECT entity_id FROM approval_rule_requesters WHERE rule_id = ?",
                String.class, ruleId
        );
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }
}
