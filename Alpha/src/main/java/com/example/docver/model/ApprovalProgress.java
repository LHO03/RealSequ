package com.example.docver.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 의사코드 ApprovalProgress 구조체 (line 2905) 이식.
 *
 * 승인 진행 상황 종합 응답:
 *   - rule: 규칙 메타 (id, file_id, status, mode 등)
 *   - counters: 카운터 (required, received_approvals, received_rejections, total_approvers, mode)
 *   - decisions: 결정 이력 (각 승인자의 결정 내역)
 *   - pending: 미결 승인자 (아직 결정 안 한 사람들)
 *
 * 의사코드는 std::map<string, string>의 중첩으로 표현했으나
 * Java에서는 더 명확한 타입(Map<String, Object>, List<Map>)으로 표현.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalProgress {
    private Map<String, Object> rule;       // 빈 맵이면 진행 중인 승인 없음
    private Map<String, Object> counters;   // {required, received_approvals, received_rejections, total_approvers, mode}
    private List<Map<String, Object>> decisions;  // 결정 이력
    private List<Map<String, Object>> pending;    // 미결 승인자

    /**
     * 빈 진행 상황 (승인 이력 자체가 없는 경우).
     */
    public static ApprovalProgress empty() {
        return ApprovalProgress.builder()
                .rule(java.util.Map.of())
                .counters(java.util.Map.of())
                .decisions(java.util.List.of())
                .pending(java.util.List.of())
                .build();
    }
}
