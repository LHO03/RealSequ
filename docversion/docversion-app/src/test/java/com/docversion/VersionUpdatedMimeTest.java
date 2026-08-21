package com.docversion;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.event.VersionEvents;
import com.docversion.service.DocumentVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 회귀: 파일 형식이 바뀐 수정에서 VersionUpdated 이벤트가 이전 버전의 실제 MIME을
 * fromMimeType으로 전달하는지 검증한다.
 *
 * <p>과거 버그: 이전·새 MIME 모두에 새 파일의 MIME을 넣어(newContent.mimeType() 이중 전달),
 * diff 리스너가 이전 파일을 틀린 형식으로 파싱할 수 있었다. 이 테스트는 그 회귀를 막는다.
 */
@SpringBootTest
@Testcontainers
@RecordApplicationEvents
class VersionUpdatedMimeTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("nextcloud")
            .withUsername("nextcloud")
            .withPassword("nextcloud");

    /**
     * 07/23: 버전 콘텐츠 저장용 임시 경로.
     * <p>{@code @DynamicPropertySource} 메서드는 {@link DynamicPropertyRegistry} 인자를
     * <b>하나만</b> 받을 수 있다. 과거엔 {@code @TempDir Path} 파라미터를 함께 받아
     * 컨텍스트 부트스트랩 단계에서 IllegalStateException으로 전 테스트가 기동조차 못 했다.
     */
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
    @Autowired ApplicationEvents events;

    @Test
    void versionUpdated_carriesPreviousMime_notNewMime() {
        // v1: markdown → v2: plain (형식 변경)
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/note.md",
                FileContent.ofText("# 제목\n본문\n", "text/markdown"));

        versions.onDocumentModified(
                "alice", v1.getFileId(),
                FileContent.ofText("본문만\n", "text/plain"));

        VersionEvents.VersionUpdated e = events.stream(VersionEvents.VersionUpdated.class)
                .findFirst().orElseThrow();

        // 이전 자리에는 이전 버전(markdown), 새 자리에는 새 버전(plain)이 들어가야 한다.
        assertThat(e.fromMimeType()).isEqualTo("text/markdown");
        assertThat(e.toMimeType()).isEqualTo("text/plain");
    }
}
