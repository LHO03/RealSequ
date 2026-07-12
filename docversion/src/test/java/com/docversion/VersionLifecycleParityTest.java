package com.docversion;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.mapper.VersionDiffMapper;
import com.docversion.service.DocumentVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 버전 생명주기 parity 통합 테스트.
 * <p>실제 MariaDB(Testcontainers)에서 돌려 MariaDB 고유 SQL(FOR UPDATE, INSERT IGNORE,
 * JSON_SET)이 실제로 동작하는지 검증한다(H2 미사용). C++ 프로토타입을 명세로 보고
 * 동작 동등성(revision_no 단조 증가, diff 캐시 적재)을 확인한다.
 */
@SpringBootTest
@Testcontainers
class VersionLifecycleParityTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("nextcloud")
            .withUsername("nextcloud")
            .withPassword("nextcloud");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry, @org.junit.jupiter.api.io.TempDir Path tmp) {
        registry.add("spring.datasource.url", mariadb::getJdbcUrl);
        registry.add("spring.datasource.username", mariadb::getUsername);
        registry.add("spring.datasource.password", mariadb::getPassword);
        registry.add("docversion.storage.base-path", () -> tmp.resolve("storage").toString());
    }

    @Autowired
    DocumentVersionService service;

    @Autowired
    VersionDiffMapper versionDiffMapper;

    @Test
    void createThenModify_assignsMonotonicRevisions_andCachesDiff() {
        // 1. 최초 버전 생성 (revision_no = 1)
        VersionInfo v1 = service.createInitialVersion(
                "alice", "/projects/spec.txt",
                FileContent.ofText("line1\nline2\nline3\n", "text/plain"));

        assertThat(v1.getRevisionNo()).isEqualTo(1);
        assertThat(v1.getFileId()).isNotBlank();
        assertThat(v1.getVersionId()).isNotBlank();

        // 2. 수정 → revision_no = 2 (단조 증가, FOR UPDATE 경로)
        VersionInfo v2 = service.onDocumentModified(
                "alice", v1.getFileId(),
                FileContent.ofText("line1\nline2-edited\nline3\nline4\n", "text/plain"));

        assertThat(v2.getRevisionNo()).isEqualTo(2);
        assertThat(v2.getVersionId()).isNotEqualTo(v1.getVersionId());

        // 3. diff 캐시가 (v1 -> v2)로 적재되었는지 (AFTER_COMMIT 리스너, INSERT IGNORE)
        Map<String, Object> cached = versionDiffMapper.findCached(
                v1.getFileId(), v1.getVersionId(), v2.getVersionId());
        assertThat(cached).isNotNull();
        assertThat(cached.get("diffMethod")).isEqualTo("myers");
        // line2 수정(1 add + 1 del) + line4 추가(1 add) → added=2, deleted=1
        assertThat(((Number) cached.get("addedLines")).intValue()).isEqualTo(2);
        assertThat(((Number) cached.get("deletedLines")).intValue()).isEqualTo(1);

        // 4. getVersionsAtTime: 두 버전 모두 조회 (최신순)
        long now = System.currentTimeMillis() / 1000 + 1;
        List<VersionInfo> versions = service.getVersionsAtTime("alice", v1.getFileId(), now, 50, 0);
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getRevisionNo()).isEqualTo(2); // 최신 먼저
        assertThat(versions.get(1).getRevisionNo()).isEqualTo(1);
        // metadata author fallback 확인
        assertThat(versions.get(0).getMetadataMap()).containsEntry("author", "alice");
    }

    @Test
    void modifyMissingDocument_throwsNotFound() {
        // 07/12 - C-3: 인증 3-A(소유권 검사) 도입 이후, 없는 문서 수정은
        //   "빈 결과"가 아니라 IllegalArgumentException(컨트롤러에서 404)이다.
        assertThatThrownBy(() -> service.onDocumentModified(
                "bob", "non-existent-file-id",
                FileContent.ofText("x", "text/plain")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("문서를 찾을 수 없습니다");
    }
}
