package com.docversion.web;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.service.DocumentVersionService;
import com.docversion.service.ForbiddenOperationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 버전 생명주기 REST 진입점.
 * <p>C++ DocumentVersionWorkflowAPI의 public 메서드를 HTTP로 노출.
 * 인증 2단계: 쓰기 창구의 작성자는 클라이언트가 보낸 값이 아니라 로그인 세션의 신원으로 결정한다.
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
     * 같은 (사용자 + 경로)면 새 버전, 새 경로면 새 문서로 자동 분기.
     * 작성자 = 로그인 사용자(principal). 클라이언트가 userId를 보내도 무시한다.
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

    /** RD-SRS-9.2: 문서 수정 → 새 버전. 작성자 = 로그인 사용자.
     *  인증 3단계(3-A): 소유자가 아니면 403, 문서가 없으면 404. */
    @PostMapping("/{fileId}/versions")
    public VersionInfo onDocumentModified(Principal principal,
                                          @PathVariable String fileId,
                                          @RequestParam(required = false) String reason,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        try {
            return service.onDocumentModified(principal.getName(), fileId, content, reason);
        } catch (ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** RD-SRS-9.5: 특정 시점 버전 목록. 07/19 - P1-②: 로그인 + 소유자/이해관계자/ADMIN만. */
    @GetMapping("/{fileId}/versions")
    public List<VersionInfo> getVersions(Principal principal,
                                         @PathVariable String fileId,
                                         @RequestParam(defaultValue = "0") long targetTimestamp,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        long ts = targetTimestamp > 0 ? targetTimestamp : System.currentTimeMillis() / 1000;
        try {
            return service.getVersionsAtTime(principal.getName(), fileId, ts, limit, offset);
        } catch (ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** 07/12 - RD-SRS-9.3: 문서 활동 이력 조회 (변경자·일시·행위·사유). */
    @GetMapping("/{fileId}/activity")
    public List<Map<String, Object>> getActivity(Principal principal,
                                                 @PathVariable String fileId,
                                                 @RequestParam(defaultValue = "50") int limit,
                                                 @RequestParam(defaultValue = "0") int offset) {
        try {
            return service.getActivity(principal.getName(), fileId, limit, offset);
        } catch (ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /**
     * RD-SRS-9.4: 두 버전 간 diff 조회 (version_diffs 캐시).
     * 캐시 miss면 204 No Content.
     */
    @GetMapping("/{fileId}/diff")
    public ResponseEntity<Map<String, Object>> getDiff(Principal principal,
                                                       @PathVariable String fileId,
                                                       @RequestParam String fromVersionId,
                                                       @RequestParam String toVersionId) {
        Map<String, Object> diff;
        try {
            diff = service.getDiff(principal.getName(), fileId, fromVersionId, toVersionId);
        } catch (ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return diff == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(diff);
    }

    /**
     * 07/12 - RD-SRS-9.5 열람: 특정 버전의 실제 내용(파일 바이트) 다운로드.
     * GET /api/documents/{fileId}/versions/{versionId}/content
     * 접근: 로그인 필수(SecurityConfig) + 소유자/구독자(서비스 검사).
     * 파일명은 RFC 5987(filename*)로 한글 경로도 안전하게 전달한다.
     */
    @GetMapping("/{fileId}/versions/{versionId}/content")
    public ResponseEntity<byte[]> getVersionContent(@PathVariable String fileId,
                                                    @PathVariable String versionId,
                                                    Principal principal) {
        DocumentVersionService.VersionContent c;
        try {
            c = service.getVersionContent(principal.getName(), fileId, versionId);
        } catch (ForbiddenOperationException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        String encoded = URLEncoder.encode(c.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.mimetype()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(c.bytes());
    }
}
