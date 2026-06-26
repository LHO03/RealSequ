package com.docversion.web;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.service.DocumentVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
                                                       @RequestParam(defaultValue = "") String folder,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.upload(principal.getName(), folder, file.getOriginalFilename(), content);
    }

    /** RD-SRS-9.1: 최초 버전 생성. (명시적 경로 지정 — 내부/테스트용) 작성자 = 로그인 사용자. */
    @PostMapping
    public VersionInfo createInitialVersion(Principal principal,
                                            @RequestParam String path,
                                            @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.createInitialVersion(principal.getName(), path, content);
    }

    /** RD-SRS-9.2: 문서 수정 → 새 버전. 작성자 = 로그인 사용자. */
    @PostMapping("/{fileId}/versions")
    public VersionInfo onDocumentModified(Principal principal,
                                          @PathVariable String fileId,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.onDocumentModified(principal.getName(), fileId, content);
    }

    /** RD-SRS-9.5: 특정 시점 버전 목록. targetTimestamp=0이면 전체 최신순. (읽기 — 비로그인 허용) */
    @GetMapping("/{fileId}/versions")
    public List<VersionInfo> getVersions(Principal principal,
                                         @PathVariable String fileId,
                                         @RequestParam(defaultValue = "0") long targetTimestamp,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        long ts = targetTimestamp > 0 ? targetTimestamp : System.currentTimeMillis() / 1000;
        String who = principal != null ? principal.getName() : "anonymous";
        return service.getVersionsAtTime(who, fileId, ts, limit, offset);
    }

    /**
     * RD-SRS-9.4: 두 버전 간 diff 조회 (version_diffs 캐시).
     * 캐시 miss면 204 No Content.
     */
    @GetMapping("/{fileId}/diff")
    public ResponseEntity<Map<String, Object>> getDiff(@PathVariable String fileId,
                                                       @RequestParam String fromVersionId,
                                                       @RequestParam String toVersionId) {
        Map<String, Object> diff = service.getDiff(fileId, fromVersionId, toVersionId);
        return diff == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(diff);
    }
}
