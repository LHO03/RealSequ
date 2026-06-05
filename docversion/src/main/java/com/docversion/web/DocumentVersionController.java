package com.docversion.web;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.service.DocumentVersionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 버전 생명주기 REST 진입점.
 * <p>C++ DocumentVersionWorkflowAPI의 public 메서드를 HTTP로 노출.
 * 알파 단계 최소 표면 — 인증/권한(Spring Security)은 후속 보안 슬라이스에서 추가.
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
     */
    @PostMapping("/upload")
    public DocumentVersionService.UploadOutcome upload(@RequestParam String userId,
                                                       @RequestParam(defaultValue = "") String folder,
                                                       @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.upload(userId, folder, file.getOriginalFilename(), content);
    }

    /** RD-SRS-9.1: 최초 버전 생성. (명시적 경로 지정 — 내부/테스트용) */
    @PostMapping
    public VersionInfo createInitialVersion(@RequestParam String userId,
                                            @RequestParam String path,
                                            @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.createInitialVersion(userId, path, content);
    }

    /** RD-SRS-9.2: 문서 수정 → 새 버전. */
    @PostMapping("/{fileId}/versions")
    public VersionInfo onDocumentModified(@PathVariable String fileId,
                                          @RequestParam String userId,
                                          @RequestParam("file") MultipartFile file) throws IOException {
        FileContent content = new FileContent(file.getBytes(), file.getContentType());
        return service.onDocumentModified(userId, fileId, content);
    }

    /** RD-SRS-9.5: 특정 시점 버전 목록. targetTimestamp=0이면 전체 최신순. */
    @GetMapping("/{fileId}/versions")
    public List<VersionInfo> getVersions(@PathVariable String fileId,
                                         @RequestParam String userId,
                                         @RequestParam(defaultValue = "0") long targetTimestamp,
                                         @RequestParam(defaultValue = "50") int limit,
                                         @RequestParam(defaultValue = "0") int offset) {
        // targetTimestamp=0 → 현재 시각 기준(이하 전체)
        long ts = targetTimestamp > 0 ? targetTimestamp : System.currentTimeMillis() / 1000;
        return service.getVersionsAtTime(userId, fileId, ts, limit, offset);
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
