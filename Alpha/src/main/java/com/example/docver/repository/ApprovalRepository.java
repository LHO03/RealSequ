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
     * 05/15: consensus_mode + required_approvals를 매개변수로 받도록 확장 (시나리오 2 지원).
     */
    public void insertRule(String ruleId, String fileId,
                           String tagPending, String tagApproved, String tagRejected,
                           String consensusMode, int requiredApprovals) {
        jdbc.update(
                "INSERT INTO approval_rules " +
                "(id, file_id, tag_pending, tag_approved, tag_rejected, status, " +
                " consensus_mode, required_approvals, received_approvals, received_rejections) " +
                "VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, 0, 0)",
                ruleId, fileId, tagPending, tagApproved, tagRejected,
                consensusMode, requiredApprovals
        );
    }

    /**
     * Backward compat: 기존 호출 (THRESHOLD+1 고정).
     */
    public void insertRule(String ruleId, String fileId,
                           String tagPending, String tagApproved, String tagRejected) {
        insertRule(ruleId, fileId, tagPending, tagApproved, tagRejected, "THRESHOLD", 1);
    }

    public void insertRequester(String ruleId, String userId) {
        jdbc.update(
                "INSERT INTO approval_rule_requesters (rule_id, entity_type, entity_id) " +
                "VALUES (?, 'user', ?)",
                ruleId, userId
        );
    }

    /**
     * 승인자 등록.
     * 05/15: sequence_order 매개변수 추가 (SEQUENTIAL 모드 지원).
     * 중복 dedup은 INSERT IGNORE로 처리.
     */
    public void insertApprover(String ruleId, String userId, int sequenceOrder) {
        jdbc.update(
                "INSERT IGNORE INTO approval_rule_approvers " +
                "(rule_id, entity_type, entity_id, sequence_order) " +
                "VALUES (?, 'user', ?, ?)",
                ruleId, userId, sequenceOrder
        );
    }

    /**
     * Backward compat: sequence_order=0.
     */
    public void insertApprover(String ruleId, String userId) {
        insertApprover(ruleId, userId, 0);
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

    // ============================================================
    // 진행 상황 조회 (의사코드 getApprovalProgress, line 2912)
    // ============================================================

    /**
     * 파일에 대한 가장 관련성 높은 규칙 1건 조회.
     * OPEN 규칙 우선, 없으면 가장 최근 CLOSED/CANCELLED.
     * 의사코드 line 2916~2924와 동일.
     */
    public Optional<Map<String, Object>> findLatestRuleByFileId(String fileId) {
        List<Map<String, Object>> rules = jdbc.queryForList(
                "SELECT id, file_id, status, consensus_mode, " +
                "       required_approvals, received_approvals, received_rejections " +
                "FROM approval_rules WHERE file_id = ? " +
                "ORDER BY (status = 'OPEN') DESC, id DESC LIMIT 1",
                fileId
        );
        return rules.isEmpty() ? Optional.empty() : Optional.of(rules.get(0));
    }

    /**
     * 규칙의 총 승인자 수.
     */
    public long countApproversByRuleId(String ruleId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_rule_approvers WHERE rule_id = ?",
                Long.class, ruleId
        );
        return count == null ? 0 : count;
    }

    /**
     * 규칙의 결정 이력 (시간순).
     */
    public List<Map<String, Object>> findDecisionHistoryByRuleId(String ruleId) {
        return jdbc.queryForList(
                "SELECT user_id, action, `timestamp`, comment " +
                "FROM approval_activity WHERE rule_id = ? " +
                "ORDER BY `timestamp` ASC",
                ruleId
        );
    }

    /**
     * 규칙의 미결 승인자 목록 (sequence_order 순).
     * 의사코드 line 2955와 동일한 NOT IN 서브쿼리.
     */
    public List<Map<String, Object>> findPendingApproversByRuleId(String ruleId) {
        return jdbc.queryForList(
                "SELECT entity_id AS user_id, sequence_order " +
                "FROM approval_rule_approvers " +
                "WHERE rule_id = ? " +
                "  AND entity_id NOT IN (" +
                "    SELECT user_id FROM approval_activity " +
                "    WHERE rule_id = ? AND action IN ('approved', 'rejected')" +
                "  ) " +
                "ORDER BY sequence_order ASC, entity_id ASC",
                ruleId, ruleId
        );
    }

    // ============================================================
    // 시나리오 4 지원: CANCEL (의사코드 cancelApprovalRequest, line 1057)
    // ============================================================

    /**
     * 파일의 OPEN 규칙 ID 조회 (cancelApprovalRequest용).
     * 의사코드 line 1061~1065.
     */
    public Optional<String> findOpenRuleIdByFileId(String fileId) {
        List<String> result = jdbc.queryForList(
                "SELECT id FROM approval_rules WHERE file_id = ? AND status = 'OPEN' LIMIT 1",
                String.class, fileId
        );
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /**
     * 사용자가 규칙의 요청자인지 확인.
     */
    public boolean isRequester(String ruleId, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_rule_requesters " +
                "WHERE rule_id = ? AND entity_id = ?",
                Integer.class, ruleId, userId
        );
        return count != null && count > 0;
    }

    /**
     * 사용자가 규칙의 승인자인지 확인.
     */
    public boolean isApprover(String ruleId, String userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_rule_approvers " +
                "WHERE rule_id = ? AND entity_id = ?",
                Integer.class, ruleId, userId
        );
        return count != null && count > 0;
    }

    /**
     * 규칙을 CANCELLED 상태로 전이 (OPEN인 경우에만).
     * 의사코드 line 1099~1107의 race condition 방어 패턴 유지.
     *
     * @return 변경된 row 수 (0이면 race condition - 다른 호출이 먼저 닫음)
     */
    public int cancelRule(String ruleId) {
        return jdbc.update(
                "UPDATE approval_rules SET status = 'CANCELLED' " +
                "WHERE id = ? AND status = 'OPEN'",
                ruleId
        );
    }

    // ============================================================
    // 시나리오 2 지원: 합의 평가 (의사코드 evaluateConsensus, line 3287)
    // ============================================================

    /**
     * 사용자의 sequence_order 조회 (SEQUENTIAL 모드용).
     * 의사코드 isUserTurnInSequence의 line 3257~3260.
     */
    public Optional<Integer> findSequenceOrderForUser(String ruleId, String userId) {
        List<Integer> result = jdbc.queryForList(
                "SELECT sequence_order FROM approval_rule_approvers " +
                "WHERE rule_id = ? AND entity_id = ? LIMIT 1",
                Integer.class, ruleId, userId
        );
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    /**
     * 본인보다 앞 순서의 미결 승인자 수 조회.
     * 의사코드 isUserTurnInSequence의 line 3265~3273.
     *
     * @return 본인 앞에 아직 결정 안 한 승인자 수 (0이면 본인 차례)
     */
    public int countPendingBefore(String ruleId, int mySequenceOrder) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM approval_rule_approvers a " +
                "WHERE a.rule_id = ? AND a.sequence_order < ? " +
                "  AND a.entity_id NOT IN (" +
                "    SELECT user_id FROM approval_activity " +
                "    WHERE rule_id = ? AND action IN ('approved', 'rejected')" +
                "  )",
                Integer.class, ruleId, mySequenceOrder, ruleId
        );
        return count == null ? 0 : count;
    }
}
