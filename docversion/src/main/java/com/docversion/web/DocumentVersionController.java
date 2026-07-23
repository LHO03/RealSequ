package com.docversion.web;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.service.DocumentVersionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 버전 생명주기 REST 진입점.
 * <p>인증 2단계: 쓰기 창구의 작성자는 로그인 세션의 신원으로 결정한다.
 * <p>P1: 예외→HTTP 매핑은 GlobalExceptionHandler(어드바이스)로 일원화했다.
 * (없음→404, 열린 승인 요청으로 업로드 거부(정책 A)→409, 소유자 아님→403)
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentVersionController {

    private final DocumentVersionService service;

    public DocumentVersionController(DocumentVersionService service) {
        this.service = service;
    }

    /**
     * Nextcloud식 업로드 (권장 진입점). 경로는 서버가 정규화해서 결정한다.
     * 같은 (사용자 + 경로)면 새 버전, 새 경로면 새 문서. 작성자 = 로그인 사용자.
     */
    @PostMapping("/upload")
    public DocumentVersionService.UploadOutcome upload(Principal principal,
                                                       @RequestParam(required = false) String reason,
                                                       @RequestParam(defaultValue = "") String folder,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.upload(principal.getName(), folder, file.getOriginalFilename(), content, reason);
    }

    /** RD-SRS-9.1: 최초 버전 생성. (명시적 경로 지정 — 내부/테스트용) 작성자 = 로그인 사용자. */
    @PostMapping
    public VersionInfo createInitialVersion(Principal principal,
                                            @RequestParam(required = false) String reason,
                                            @RequestParam String path,
                                            @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.createInitialVersion(principal.getName(), path, content, reason);
    }

    /** RD-SRS-9.2: 문서 수정 → 새 버전. 작성자 = 로그인 사용자. */
    @PostMapping("/{fileId}/versions")
    public VersionInfo onDocumentModified(Principal principal,
                                          @PathVariable String fileId,
                                          @RequestParam(required = false) String reason,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.onDocumentModified(principal.getName(), fileId, content, reason);
    }

    /** RD-SRS-9.5: 특정 시점 버전 목록. 07/19 - P1-②: 로그인 + 소유자/이해관계자/ADMIN만. */
    @GetMapping("/{fileId}/versions")
    public List<VersionInfo> getVersions(Principal principal,
                                         @PathVariable String fileId,
                                         @RequestParam(defaultValue = "0") long targetTimestamp,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        long ts = targetTimestamp > 0 ? targetTimestamp : System.currentTimeMillis() / 1000;
        return service.getVersionsAtTime(principal.getName(), fileId, ts, limit, offset);
    }

    /** 07/12 - RD-SRS-9.3: 문서 활동 이력 조회 (변경자·일시·행위·사유). */
    @GetMapping("/{fileId}/activity")
    public List<Map<String, Object>> getActivity(Principal principal,
                                                 @PathVariable String fileId,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(defaultValue = "0") int offset) {
        return service.getActivity(principal.getName(), fileId, limit, offset);
    }

    /**
     * RD-SRS-9.4: 두 버전 간 diff 조회 (version_diffs 캐시). 캐시 miss면 204 No Content.
     */
    @GetMapping("/{fileId}/diff")
    public ResponseEntity<Map<String, Object>> getDiff(Principal principal,
                                                       @PathVariable String fileId,
                                                       @RequestParam String fromVersionId,
                                                       @RequestParam String toVersionId) {
        Map<String, Object> diff = service.getDiff(principal.getName(), fileId, fromVersionId, toVersionId);
        return diff == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(diff);
    }

    /**
     * P1c: 실패(FAILED)한 diff 계산 재시도. 해당 작업을 PENDING으로 되돌리면 워커가 재계산한다.
     * 반환: 재시도 후의 현재 상태(status 포함). 작업 자체가 없으면 404(어드바이스).
     */
    @PostMapping("/{fileId}/diff/retry")
    public Map<String, Object> retryDiff(Principal principal,
                                         @PathVariable String fileId,
                                         @RequestParam String fromVersionId,
                                         @RequestParam String toVersionId) {
        return service.retryDiff(principal.getName(), fileId, fromVersionId, toVersionId);
    }

    /**
     * 07/12 - RD-SRS-9.5 열람: 특정 버전의 실제 내용(파일 바이트) 다운로드.
     * 접근: 로그인 필수 + 소유자/구독자(서비스 검사). 파일명은 RFC 5987(filename*)로 한글 안전.
     */
    @GetMapping("/{fileId}/versions/{versionId}/content")
    public ResponseEntity<byte[]> getVersionContent(@PathVariable String fileId,
                                                    @PathVariable String versionId,
                                                    Principal principal) {
        DocumentVersionService.VersionContent c =
                service.getVersionContent(principal.getName(), fileId, versionId);
        String encoded = URLEncoder.encode(c.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.mimetype()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(c.bytes());
    }
}
