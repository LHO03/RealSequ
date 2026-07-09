package com.docversion.service;

import com.docversion.mapper.ActivityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 변경 이력(activity) 기록 전담 서비스 — 목표 간극(나) 해소 (RD-SRS-9.3).
 *
 * <p>이전에는 VersionWriteService·RetentionPurgeService가 각자 ActivityMapper를 직접 호출해
 * 기록 형식(subject 접두, affecteduser, object_type)이 서로 갈라져 있었다. 기록 통로를 이
 * 서비스 한 곳으로 모아 "이력은 여기서, 이 형식으로"를 강제한다. 앞으로 이력을 남길 모듈
 * (승인 확장, 태그 등)도 이 서비스를 쓰면 형식이 저절로 통일된다.
 *
 * <p><b>통일 형식</b>: subject = "file_" + action, affecteduser = 행위자(시스템 작업은 "system"),
 * object_type = "files" (Nextcloud activity 관례), subjectparams = action/fileId/(versionId)/(부가) JSON.
 *
 * <p><b>트랜잭션</b>: 의도적으로 {@code @Transactional}을 걸지 않는다 — 항상 호출자의
 * 트랜잭션에 합류해야 업무 변경과 이력이 원자적으로 커밋된다. 트랜잭션 밖에서 호출하면
 * 이력만 따로 커밋될 수 있으므로, 반드시 트랜잭션 경계 안에서 호출할 것.
 */
@Service
public class AuditLogService {

    private final ActivityMapper activity;
    private final ObjectMapper json;

    public AuditLogService(ActivityMapper activity, ObjectMapper json) {
        this.activity = activity;
        this.json = json;
    }

    /**
     * 파일 대상 활동 기록.
     *
     * @param userId    행위자. null/공백이면 "system"(스케줄러 등 자동 작업).
     * @param fileId    대상 문서.
     * @param action    행위 코드 (예: "created", "version_updated", "retention_deleted").
     *                  저장 시 "file_" 접두가 붙는다.
     * @param versionId 관련 버전(없으면 null).
     * @param extra     부가 정보(없으면 null). 예: reason, deleted 개수.
     */
    public void record(String userId, String fileId, String action,
                       String versionId, Map<String, String> extra) {
        long now = Instant.now().getEpochSecond();
        String actor = (userId == null || userId.isBlank()) ? "system" : userId;

        Map<String, String> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("fileId", fileId);
        if (versionId != null && !versionId.isBlank()) {
            m.put("versionId", versionId);
        }
        if (extra != null) {
            for (Map.Entry<String, String> e : extra.entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    m.put(e.getKey(), e.getValue());
                }
            }
        }

        activity.insertActivity(now, actor, actor,
                "file_" + action, toJson(m), fileId, "files", fileId);
    }

    private String toJson(Map<String, String> m) {
        try {
            return json.writeValueAsString(m);
        } catch (Exception e) {
            return "{}"; // 이력의 부가정보 직렬화 실패가 본 업무를 막아서는 안 됨
        }
    }
}
