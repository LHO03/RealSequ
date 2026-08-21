package com.docversion.web;

import com.docversion.dlp.DbRuleProvider;
import com.docversion.mapper.DlpScanMapper;
import com.docversion.service.DocumentAccessPolicy;
import com.docversion.service.ForbiddenOperationException;
import com.docversion.service.ResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DLP 검사 결과 조회 및 규칙 관리. (RD-SRS-5.1, 5.4, 5.6)
 *
 * <p>경로는 대상의 귀속에 따라 나눈다.
 * 문서에 귀속되는 것은 기존 문서 접근 정책을 그대로 재사용하고,
 * 전역에 해당하는 규칙 관리는 관리자 권한을 요구한다.
 * 새 기능을 추가할 때 인가 로직을 다시 작성하지 않아도 되는 것이
 * 접근 정책을 단일 관문으로 구성해 둔 이점이다.
 *
 * <p><b>응답에 탐지 원문을 포함하지 않는다.</b> 조회 권한이 있는 사용자라도
 * 민감 데이터의 원문을 화면에 노출할 이유가 없다. 규칙 이름, 심각도, 위치,
 * 마스킹된 값만 돌려준다. 탐지 결과 자체가 2차 유출 경로가 되어서는 안 된다.
 */
@RestController
@RequestMapping("/api")
public class DlpController {

    private final DlpScanMapper scans;
    private final DocumentAccessPolicy access;
    private final DbRuleProvider ruleProvider;

    public DlpController(DlpScanMapper scans,
                         DocumentAccessPolicy access,
                         DbRuleProvider ruleProvider) {
        this.scans = scans;
        this.access = access;
        this.ruleProvider = ruleProvider;
    }

    /**
     * 버전별 검사 결과. (RD-SRS-5.1)
     *
     * <p>클라이언트가 차단 판단(5.3)에 사용하는 값은 scope=FULL의 verdict다.
     * verdict가 UNDETERMINED이면 "안전"이 아니라 "판정하지 못함"이므로
     * 호출부는 이를 구분해 다뤄야 한다.
     */
    @GetMapping("/documents/{fileId}/versions/{versionId}/dlp")
    public Map<String, Object> getScan(Principal principal,
                                       @PathVariable String fileId,
                                       @PathVariable String versionId,
                                       @RequestParam(defaultValue = "FULL") String scope) {
        access.requireRead(fileId, principal.getName());

        Map<String, Object> scan = scans.findByVersionAndScope(versionId, scope);
        if (scan == null) {
            throw new ResourceNotFoundException("검사 결과가 없습니다: version=" + versionId + " scope=" + scope);
        }
        // 다른 문서의 버전을 조회하려는 시도 차단.
        if (!fileId.equals(String.valueOf(scan.get("fileId")))) {
            throw new ResourceNotFoundException("검사 결과가 없습니다: version=" + versionId);
        }

        Map<String, Object> out = new HashMap<>(scan);
        long scanId = ((Number) scan.get("id")).longValue();
        out.put("findings", scans.findFindings(scanId));
        return out;
    }

    /** 문서 단위 검사 이력. (RD-SRS-5.1) */
    @GetMapping("/documents/{fileId}/dlp")
    public List<Map<String, Object>> listScans(Principal principal,
                                               @PathVariable String fileId) {
        access.requireRead(fileId, principal.getName());
        return scans.findByFile(fileId);
    }

    /**
     * 재검사 요청. 기존 작업을 PENDING으로 되돌린다.
     *
     * <p>소유자로 제한한다. 검사는 계산 자원을 소모하므로 열람 권한만으로
     * 반복 요청할 수 있게 두지 않는다.
     */
    @PostMapping("/documents/{fileId}/versions/{versionId}/dlp/rescan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> rescan(Principal principal,
                                      @PathVariable String fileId,
                                      @PathVariable String versionId,
                                      @RequestParam(defaultValue = "FULL") String scope) {
        // requireOwner는 접근 정책에 없다. 열람 자격을 먼저 확인한 뒤
        // 소유자 여부를 별도로 대조한다. 재검사는 계산 자원을 소모하므로
        // 구독자가 반복 요청할 수 있게 두지 않는다.
        String owner = access.requireRead(fileId, principal.getName());
        if (!owner.equals(principal.getName())) {
            throw new ForbiddenOperationException("재검사는 문서 소유자만 요청할 수 있습니다.");
        }

        long now = Instant.now().getEpochSecond();
        int updated = scans.resetToPending(versionId, scope, now);
        if (updated == 0) {
            // 검사 이력이 없으면 새로 적재한다.
            scans.insertPending(fileId, versionId, scope, now);
        }
        return Map.of("ok", true, "status", "PENDING");
    }

    // ----------------------------------------------------------
    // 규칙 관리 (RD-SRS-5.6은 리얼시큐 Web 담당이나, 서버는 조회 수단을 제공해야 한다)
    //
    // 관리자 제한은 메서드 어노테이션이 아니라 SecurityConfig의 경로 인가로 건다.
    // 보존정책(/api/retention/**)이 같은 방식이며, 인가 규칙을 한 곳에 모아두면
    // 어느 경로가 어떤 권한을 요구하는지 한눈에 보인다.
    // ----------------------------------------------------------

    /** 현재 적재된 규칙 요약. 정규식 원문은 관리자만 볼 수 있다. */
    @GetMapping("/dlp/rules")
    public Map<String, Object> rules() {
        return Map.of(
                "threshold", ruleProvider.threshold(),
                "count", ruleProvider.activeRules().size(),
                "rules", ruleProvider.activeRules().stream()
                        .map(r -> Map.of(
                                "name", r.name(),
                                "displayName", r.displayName(),
                                "severity", r.severity().name(),
                                "score", r.score(),
                                "scoreVerified", r.scoreVerified(),
                                "validator", r.validatorName() == null ? "" : r.validatorName(),
                                "hasContext", r.hasContextCondition()))
                        .toList());
    }

    /**
     * 규칙 재적재. 관리 화면에서 규칙을 고친 뒤 호출한다.
     *
     * <p>규칙을 DB에 둔 이유가 무중단 갱신이므로, 재배포 없이 반영할 통로가 필요하다.
     */
    @PostMapping("/dlp/rules/reload")
    public Map<String, Object> reload() {
        int n = ruleProvider.reload();
        return Map.of("ok", true, "count", n);
    }
}
