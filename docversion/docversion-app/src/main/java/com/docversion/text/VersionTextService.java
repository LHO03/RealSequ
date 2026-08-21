package com.docversion.text;

import com.docversion.diff.DiffService;
import com.docversion.diff.DiffTypes.ExtractionResult;
import com.docversion.diff.DocumentTextExtractor;
import com.docversion.domain.FileContent;
import com.docversion.mapper.FilesVersionMapper;
import com.docversion.storage.StorageException;
import com.docversion.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * 버전별 추출 텍스트 공급자. 처음 필요할 때 추출하고 결과를 재사용한다.
 * (RD-SRS-9.4 버전 비교, RD-SRS-5.1 · 5.2 민감 데이터 판별 공용)
 *
 * <p><b>왜 필요한가.</b> 버전 하나는 여러 비교에 등장한다. v2는 v1↔v2와 v2↔v3에
 * 모두 쓰이므로 두 번 파싱된다. 여기에 DLP 검사가 붙으면 세 번이 된다.
 * 성능평가지표가 100MB 문서의 동시 요청을 전제하므로, 압축 해제와 XML 파싱을
 * 반복하는 비용은 무시할 수 없다.
 *
 * <p><b>왜 전담 워커를 두지 않는가.</b> 업로드 직후 일괄 추출하는 방식도
 * 검토했으나, 그렇게 하면 이미 저장된 과거 버전 전체가 추출 대상이 되어
 * 워커가 장시간 자원을 점유한다. 지연 추출은 실제로 비교나 검사가 요청된
 * 버전만 처리하므로 그런 소급 처리가 발생하지 않는다.
 *
 * <p><b>원본 바이트는 여전히 호출부가 읽는다.</b> SHA-256 계산에 원본이
 * 필요하기 때문이다. 다만 해시 계산은 파싱보다 훨씬 저렴하므로, 비싼 쪽인
 * 텍스트 추출만 1회로 줄이는 것이 이 클래스의 목적이다.
 * 이미 읽어둔 내용을 인자로 받아 저장소를 두 번 읽지 않는다.
 *
 * <p><b>추출 불가와 안전을 혼동하지 않는다.</b> 추출에 실패했거나 지원하지
 * 않는 형식이면 null을 돌려주고 상태를 남긴다. 호출부는 이를 빈 텍스트로
 * 취급해서는 안 된다. 검사되지 않은 문서가 민감하지 않은 것으로 표시되면
 * 유출 차단이 무력화된다.
 */
@Service
public class VersionTextService {

    private static final Logger log = LoggerFactory.getLogger(VersionTextService.class);

    /** files_versions.text_status 값. V15에서 정의. */
    public static final String PENDING = "PENDING";
    public static final String EXTRACTED = "EXTRACTED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String FAILED = "FAILED";

    private final FilesVersionMapper versions;
    private final StorageService storage;
    private final DocumentTextExtractor extractor;

    public VersionTextService(FilesVersionMapper versions,
                              StorageService storage,
                              DocumentTextExtractor extractor) {
        this.versions = versions;
        this.storage = storage;
        this.extractor = extractor;
    }

    /**
     * 해당 버전의 텍스트를 돌려준다. 없으면 추출해 저장한 뒤 돌려준다.
     *
     * @param versionId 버전 식별자
     * @param content   이미 읽어둔 원본. 저장소 재읽기를 피하기 위해 받는다
     * @return 추출된 텍스트. 지원하지 않는 형식이거나 추출에 실패하면 null
     */
    public String getOrExtract(String versionId, FileContent content) {
        try {
            String status = versions.selectTextStatus(versionId);

            if (EXTRACTED.equals(status)) {
                String cached = readCached(versionId);
                if (cached != null) {
                    return cached;
                }
                // 상태는 EXTRACTED인데 파일이 없는 경우(수동 삭제 등).
                // 상태를 되돌리고 아래에서 다시 추출한다.
                log.warn("추출 텍스트 파일이 없어 재추출합니다: version={}", versionId);
            } else if (UNSUPPORTED.equals(status) || FAILED.equals(status)) {
                // 이미 판정된 경우 반복 시도하지 않는다.
                return null;
            }

            return extractAndStore(versionId, content);

        } catch (RuntimeException e) {
            // 텍스트 확보 실패가 호출부(비교·검사)를 중단시켜서는 안 된다.
            // null을 돌려주면 호출부가 각자의 대체 경로로 진행한다.
            log.warn("텍스트 확보 실패: version={} {}", versionId, e.toString());
            return null;
        }
    }

    /** 저장된 추출 텍스트를 읽는다. 파일이 없으면 null. */
    private String readCached(String versionId) {
        String key = versions.selectTextKey(versionId);
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return new String(storage.readFile(key).data(), StandardCharsets.UTF_8);
        } catch (StorageException e) {
            return null;
        }
    }

    /** 추출을 수행하고 결과와 상태를 기록한다. */
    private String extractAndStore(String versionId, FileContent content) {
        long now = Instant.now().getEpochSecond();

        if (content == null || content.data() == null) {
            versions.updateTextFailed(versionId, FAILED, "원본 내용이 없습니다", now);
            return null;
        }

        String mime = content.mimeType();
        String text;

        if (extractor.canExtract(mime)) {
            // 문서 바이너리(docx, pdf 등). 추출기에 맡긴다.
            ExtractionResult ex = extractor.extractText(content);
            if (!ex.success()) {
                versions.updateTextFailed(versionId, FAILED,
                        "텍스트 추출에 실패했습니다 (mime=" + mime + ")", now);
                return null;
            }
            text = ex.text();

        } else if (!DiffService.isBinaryContent(content)) {
            // 평문 텍스트. 추출기를 거치지 않고 그대로 문자열로 읽는다.
            // 인코딩을 UTF-8로 명시한다. 플랫폼 기본값에 맡기면 실행 환경에 따라
            // 한글이 깨져 탐지가 통째로 무력화된다.
            text = new String(content.data(), StandardCharsets.UTF_8);

        } else {
            // 순수 바이너리(이미지 등). 추출 대상이 아니다.
            versions.updateTextFailed(versionId, UNSUPPORTED,
                    "텍스트 추출을 지원하지 않는 형식입니다: " + mime, now);
            return null;
        }

        // 원본과 같은 디렉터리에 접미사만 붙여 둔다. 경로가 흩어지면
        // 나중에 정리 대상을 찾기 어렵다.
        String storageKey = versions.selectStorageKey(versionId);
        if (storageKey == null || storageKey.isBlank()) {
            versions.updateTextFailed(versionId, FAILED, "storage_key가 없습니다", now);
            return null;
        }
        String key = textKeyOf(storageKey);
        try {
            storage.writeFile(key, new FileContent(
                    text.getBytes(StandardCharsets.UTF_8), "text/plain"));
        } catch (StorageException e) {
            // 저장에 실패해도 이번 요청은 처리할 수 있도록 텍스트는 돌려준다.
            // 다음 호출에서 다시 추출하게 되지만 기능은 유지된다.
            log.warn("추출 텍스트 저장 실패(이번 결과는 사용): version={} {}", versionId, e.toString());
            return text;
        }

        versions.updateTextExtracted(versionId, EXTRACTED, key, text.length(), now);
        return text;
    }

    /**
     * 추출 텍스트 저장 위치. 원본 storage_key에 접미사를 붙인다.
     *
     * <p>원본과 같은 디렉터리에 두어 위치를 예측 가능하게 한다.
     *
     * <p><b>알려진 미비점.</b> 보존정책이 버전을 정리할 때는 storage_key만
     * 삭제하므로 이 파일은 남는다. 고아 파일 정리는 별도 과제로 둔다.
     * (원본이 사라지면 재추출도 불가능하나, text_status가 EXTRACTED로 남아
     *  캐시된 텍스트를 계속 읽을 수 있어 기능상 문제는 없다.)
     */
    public static String textKeyOf(String storageKey) {
        return storageKey + ".txt";
    }

}
