package com.docversion;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.mapper.DlpScanMapper;
import com.docversion.mapper.RetentionMapper;
import com.docversion.service.DocumentVersionService;
import com.docversion.service.RetentionPolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보존 정리와 DLP 검사 이력의 접점 회귀 테스트 (RD-SRS-9.10 × 5.1).
 *
 * <p><b>이 테스트가 막는 것.</b> V15가 {@code dlp_scans.version_id → files_versions}에
 * {@code ON DELETE} 절 없이 외래키를 걸었다(기본값 RESTRICT). 그런데
 * {@code RetentionPurgeService.purgeFile}은 {@code version_diffs}와 {@code files_versions}만
 * 지우고 {@code dlp_scans}는 손대지 않았다. 그 결과 <b>검사가 한 번이라도 돌아간 버전은
 * 보존 정책이 삭제할 수 없었다</b> — 정책을 적용하는 순간 오류 1451로 트랜잭션 전체가
 * 롤백되고, 자동 정리 워커는 예외를 삼켜 경고만 남기므로 조용히 아무것도 정리되지 않았다.
 *
 * <p>V15 이후 업로드된 모든 버전이 해당되므로 사실상 9.10 전체가 멈춘 상태였다.
 * 기존 테스트가 이를 놓친 이유는 보존 정책을 실제로 적용해 보는 테스트가 없었기 때문이다.
 *
 * <p>다른 테스트들과 마찬가지로 실제 MariaDB(Testcontainers)에서 돌린다.
 * 외래키 동작이 검증 대상이므로 H2 같은 대체 DB로는 의미가 없다.
 */
@SpringBootTest
@Testcontainers
class RetentionPurgeWithDlpScansTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("nextcloud")
            .withUsername("nextcloud")
            .withPassword("nextcloud");

    /** 버전 콘텐츠 저장용 임시 경로. (@DynamicPropertySource는 인자를 하나만 받는다) */
    static final Path STORAGE_DIR = createTempStorage();

    private static Path createTempStorage() {
        try {
            return Files.createTempDirectory("docversion-test-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
        registry.add("docversion.storage.base-path", () -> STORAGE_DIR.toString());
    }

    @Autowired DocumentVersionService versions;
    @Autowired RetentionPolicyService retention;
    @Autowired RetentionMapper retentionMapper;
    @Autowired DlpScanMapper scans;

    @Test
    void purge_deletesVersion_evenWhenDlpScanExists() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/retain1.txt", FileContent.ofText("최초 내용\n", "text/plain"));
        String fileId = v1.getFileId();
        VersionInfo v2 = versions.onDocumentModified(
                "alice", fileId, FileContent.ofText("수정 내용\n", "text/plain"));

        // 버전 생성 리스너가 FULL 검사를 적재하지만, 이 테스트의 전제를 코드로 못박아 둔다.
        // insertPending은 INSERT IGNORE라 이미 있으면 무시되므로 중복 적재가 아니다.
        long now = Instant.now().getEpochSecond();
        scans.insertPending(fileId, v1.getVersionId(), "FULL", now);
        scans.insertPending(fileId, v2.getVersionId(), "FULL", now);
        assertThat(scans.findByVersionAndScope(v1.getVersionId(), "FULL"))
                .as("전제: 정리 대상 버전에 검사 이력이 있다")
                .isNotNull();

        // maxVersions=1 → 최신 1개(v2)만 남기고 v1은 정리 대상.
        // 수정 전에는 이 호출이 외래키 제약 위반으로 예외를 던졌다.
        int deleted = retention.applyToFile(fileId, 0, 0, 1, "admin");

        assertThat(deleted)
                .as("v1 한 건이 정리되어야 한다")
                .isEqualTo(1);

        List<Map<String, Object>> remaining = retentionMapper.listVersions(fileId);
        assertThat(remaining).hasSize(1);
        assertThat(String.valueOf(remaining.get(0).get("versionId")))
                .as("현재 버전은 보호된다")
                .isEqualTo(v2.getVersionId());

        assertThat(scans.findByVersionAndScope(v1.getVersionId(), "FULL"))
                .as("버전이 사라지면 그 버전의 검사 이력도 함께 사라진다")
                .isNull();
        assertThat(scans.findByVersionAndScope(v2.getVersionId(), "FULL"))
                .as("남은 버전의 검사 이력은 건드리지 않는다")
                .isNotNull();
    }

    @Test
    void purge_alsoRemovesExtractedTextFile() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/retain2.txt", FileContent.ofText("본문 하나\n", "text/plain"));
        String fileId = v1.getFileId();
        VersionInfo v2 = versions.onDocumentModified(
                "alice", fileId, FileContent.ofText("본문 둘\n", "text/plain"));

        // 추출 텍스트 캐시가 있는 상태를 만든다. 실제로는 diff 워커나 DLP 워커가 만든다.
        // 이 파일에는 문서 본문이 평문으로 들어 있으므로, 원본만 지우고 남기면
        // 보존 정리를 하고도 내용이 디스크에 그대로 남는다.
        Path original = STORAGE_DIR.resolve("objects/" + fileId + "/versions/" + v1.getVersionId());
        Path extracted = Path.of(original + ".txt");
        try {
            Files.writeString(extracted, "본문 하나\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(extracted).exists();

        retention.applyToFile(fileId, 0, 0, 1, "admin");

        assertThat(original)
                .as("원본 스냅샷이 지워져야 한다")
                .doesNotExist();
        assertThat(extracted)
                .as("추출 텍스트(.txt)도 함께 지워져야 한다 — 본문 평문이 남으면 정리의 의미가 없다")
                .doesNotExist();
        assertThat(STORAGE_DIR.resolve("objects/" + fileId + "/versions/" + v2.getVersionId()))
                .as("현재 버전의 파일은 건드리지 않는다")
                .exists();
    }
}
