package com.docversion.service;

import com.docversion.mapper.RetentionMapper;
import com.docversion.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 보존 정책 (RD-SRS-9.10).
 *
 * <p>정책(범위별 최소/최대 보관일, 최대 버전 수)을 관리하고, 정책에 따라 한도를 넘은
 * 오래된 버전을 정리한다. 보호 규칙: 문서의 현재 버전은 절대 삭제하지 않으며, min_days보다
 * 최근인 버전도 보호한다. DB 삭제는 RetentionPurgeService(트랜잭션), 실제 파일 삭제는
 * 본 서비스가 트랜잭션 밖에서 수행한다(롤백 불가 자원이므로 DB 커밋 이후 best-effort).
 *
 * <p>관리자 권한 검증은 인증 단계(Spring Security)에서 보강 예정 — 현재는 미적용.
 */
@Service
public class RetentionPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetentionPolicyService.class);
    private static final long DAY = 86_400L;

    private final RetentionMapper mapper;
    private final RetentionPurgeService purge;
    private final StorageService storage;
    private final UuidGenerator uuid;

    public RetentionPolicyService(RetentionMapper mapper, RetentionPurgeService purge,
                                  StorageService storage, UuidGenerator uuid) {
        this.mapper = mapper;
        this.purge = purge;
        this.storage = storage;
        this.uuid = uuid;
    }

    // ---------- 정책 CRUD ----------
    public String createPolicy(String scopeType, String scopeId,
                               int minDays, int maxDays, int maxVersions, boolean autoCleanup) {
        validateScope(scopeType, scopeId);
        if (minDays < 0 || maxDays < 0 || maxVersions < 0) {
            throw new InvalidRequestException("보관 기준은 0 이상이어야 합니다.");
        }
        if (maxDays > 0 && minDays > 0 && minDays > maxDays) {
            throw new InvalidRequestException("최소 보관일이 최대 보관일보다 클 수 없습니다.");
        }
        String id = uuid.newId();
        long now = Instant.now().getEpochSecond();
        mapper.insertPolicy(id, scopeType, "GLOBAL".equals(scopeType) ? null : scopeId,
                minDays, maxDays, maxVersions, autoCleanup ? 1 : 0, now);
        return id;
    }

    public Map<String, Object> getPolicy(String id) {
        Map<String, Object> p = mapper.getPolicy(id);
        if (p == null) {
            throw new ResourceNotFoundException("정책을 찾을 수 없습니다: " + id);
        }
        return p;
    }

    public List<Map<String, Object>> listPolicies(boolean activeOnly) {
        return mapper.listPolicies(activeOnly);
    }

    public void updatePolicy(String id, int minDays, int maxDays, int maxVersions, boolean autoCleanup) {
        getPolicy(id);
        mapper.updatePolicy(id, minDays, maxDays, maxVersions, autoCleanup ? 1 : 0,
                Instant.now().getEpochSecond());
    }

    public void deactivatePolicy(String id) {
        getPolicy(id);
        mapper.deactivatePolicy(id, Instant.now().getEpochSecond());
    }

    // ---------- 정책 적용(정리) ----------
    /** 정책 1건을 범위 내 모든 파일에 적용. 삭제된 버전 총수 반환. */
    public int applyPolicy(String policyId, String actingUser) {
        Map<String, Object> p = getPolicy(policyId);
        int minDays = num(p.get("minDays"));
        int maxDays = num(p.get("maxDays"));
        int maxVersions = num(p.get("maxVersions"));
        List<String> files = resolveScopeFiles(
                String.valueOf(p.get("scopeType")),
                p.get("scopeId") == null ? null : String.valueOf(p.get("scopeId")));
        int total = 0;
        for (String fileId : files) {
            total += applyToFile(fileId, minDays, maxDays, maxVersions, actingUser);
        }
        log.info("[보존] 정책 {} 적용 — 파일 {}건, 버전 {}개 삭제", policyId, files.size(), total);
        return total;
    }

    /** 단일 파일에 보관 기준을 적용. 삭제된 버전 수 반환. */
    public int applyToFile(String fileId, int minDays, int maxDays, int maxVersions, String actingUser) {
        String currentVid = mapper.currentVersionId(fileId);
        List<Map<String, Object>> versions = mapper.listVersions(fileId); // 최신순
        long now = Instant.now().getEpochSecond();

        List<String> toDeleteIds = new ArrayList<>();
        List<String> toDeleteKeys = new ArrayList<>();
        for (int i = 0; i < versions.size(); i++) {
            Map<String, Object> v = versions.get(i);
            String vid = String.valueOf(v.get("versionId"));
            long ts = lnum(v.get("timestamp"));
            long ageDays = (now - ts) / DAY;

            // 보호: 현재 버전은 절대 삭제하지 않음
            if (vid.equals(currentVid)) continue;
            // 보호: min_days보다 최근이면 보존
            if (minDays > 0 && ageDays < minDays) continue;

            boolean overCount = maxVersions > 0 && i >= maxVersions; // 최신 N개 초과
            boolean tooOld = maxDays > 0 && ageDays > maxDays;       // 최대 보관일 초과
            if (overCount || tooOld) {
                toDeleteIds.add(vid);
                toDeleteKeys.add(String.valueOf(v.get("storageKey")));
            }
        }
        if (toDeleteIds.isEmpty()) return 0;

        // 1) DB 삭제(트랜잭션) — diff 캐시 + 버전 행 + 감사 로그
        purge.purgeFile(fileId, toDeleteIds, actingUser);
        // 2) 실제 파일 삭제(트랜잭션 밖, best-effort). 실패해도 DB는 일관 — 고아 파일만 남음.
        for (String key : toDeleteKeys) {
            try {
                storage.deleteFile(key);
            } catch (RuntimeException e) {
                log.warn("[보존] 스토리지 삭제 실패(고아 파일 잔존): {} — {}", key, e.getMessage());
            }
        }
        return toDeleteIds.size();
    }

    /** 스케줄러 호출: 활성·auto_cleanup 정책을 모두 적용. */
    public int runScheduledCleanup() {
        List<Map<String, Object>> policies = mapper.listAutoCleanupPolicies();
        int total = 0;
        for (Map<String, Object> p : policies) {
            total += applyPolicy(String.valueOf(p.get("id")), "system");
        }
        return total;
    }

    // ---------- 내부 ----------
    private List<String> resolveScopeFiles(String scopeType, String scopeId) {
        switch (scopeType) {
            case "GLOBAL": return mapper.filesGlobal();
            case "USER":   return mapper.filesByOwner(scopeId);
            case "FOLDER": return mapper.filesByFolderPrefix(scopeId);
            case "FILE":   return mapper.fileExists(scopeId) > 0 ? List.of(scopeId) : List.of();
            default: throw new InvalidRequestException("알 수 없는 범위: " + scopeType);
        }
    }

    private void validateScope(String scopeType, String scopeId) {
        if (scopeType == null) throw new InvalidRequestException("범위(scopeType)를 지정해야 합니다.");
        switch (scopeType) {
            case "GLOBAL": break;
            case "USER": case "FOLDER": case "FILE":
                if (scopeId == null || scopeId.isBlank()) {
                    throw new InvalidRequestException(scopeType + " 범위는 대상 식별자(scopeId)가 필요합니다.");
                }
                break;
            default: throw new InvalidRequestException("범위는 GLOBAL/USER/FOLDER/FILE 중 하나여야 합니다.");
        }
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static long lnum(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
