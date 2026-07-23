package com.docversion;

import com.docversion.diff.DiffJobWorker;
import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.mapper.VersionDiffMapper;
import com.docversion.service.DocumentVersionService;
import com.docversion.service.ResourceNotFoundException;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1c: diff 작업 상태 기계 회귀 테스트.
 * 실패→재시도→FAILED 확정, 수동 재시도(PENDING 복귀), 원자적 점유(claim), 서비스 retryDiff 계약을 검증한다.
 * (정상 경로 PENDING→COMPLETED는 VersionLifecycleParityTest에서 검증.)
 */
@SpringBootTest
@Testcontainers
class DiffJobStateTest {

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
        // 스케줄 워커가 끼어들지 않게 첫 실행을 멀리 미룬다(수동 runOnce만).
        registry.add("docversion.diff.worker.initial-delay-ms", () -> "3600000");
    }

    @Autowired DocumentVersionService service;
    @Autowired VersionDiffMapper diffs;
    @Autowired DiffJobWorker worker;

    // 존재하지 않는 버전 쌍 → 계산 실패. MAX_ATTEMPTS(3) 초과 시 FAILED 확정, 이후 수동 재시도로 PENDING 복귀.
    @Test
    void failingJob_reachesFailed_thenResetToPending() {
        long now = Instant.now().getEpochSecond();
        diffs.insertPending("file-x", "ver-missing-from", "ver-missing-to", now);

        // 시도 1,2 → 재시도 예약(PENDING 유지), 시도 3 → FAILED 확정.
        worker.runOnce(10);
        worker.runOnce(10);
        Map<String, Object> afterTwo = diffs.findByPair("file-x", "ver-missing-from", "ver-missing-to");
        assertThat(afterTwo.get("status")).isEqualTo("PENDING");
        assertThat(((Number) afterTwo.get("attempts")).intValue()).isEqualTo(2);

        worker.runOnce(10);
        Map<String, Object> failed = diffs.findByPair("file-x", "ver-missing-from", "ver-missing-to");
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(3);
        assertThat(failed.get("lastError")).isNotNull();

        // FAILED는 더 이상 워커가 잡지 않는다(PENDING만 처리).
        //   같은 클래스의 다른 테스트가 남긴 작업이 있을 수 있으므로 runOnce의 전역 반환값이 아니라
        //   "이 행이 재점유되지 않았는지"로 확인한다(테스트 실행 순서에 의존하지 않게).
        worker.runOnce(10);
        Map<String, Object> stillFailed = diffs.findByPair("file-x", "ver-missing-from", "ver-missing-to");
        assertThat(stillFailed.get("status")).isEqualTo("FAILED");
        assertThat(((Number) stillFailed.get("attempts")).intValue()).isEqualTo(3);

        // 수동 재시도: FAILED → attempts=0, PENDING 복귀.
        int reset = diffs.resetToPending("file-x", "ver-missing-from", "ver-missing-to",
                Instant.now().getEpochSecond());
        assertThat(reset).isEqualTo(1);
        Map<String, Object> requeued = diffs.findByPair("file-x", "ver-missing-from", "ver-missing-to");
        assertThat(requeued.get("status")).isEqualTo("PENDING");
        assertThat(((Number) requeued.get("attempts")).intValue()).isZero();
        assertThat(requeued.get("lastError")).isNull();
    }

    // 원자적 점유: 같은 PENDING을 두 번 claim하면 두 번째는 0(다른 워커가 이미 선점).
    @Test
    void claim_isAtomic_secondClaimReturnsZero() {
        long now = Instant.now().getEpochSecond();
        diffs.insertPending("file-y", "vf", "vt", now);
        long id = ((Number) diffs.findByPair("file-y", "vf", "vt").get("id")).longValue();

        assertThat(diffs.claim(id, now)).isEqualTo(1);   // 첫 점유 성공
        assertThat(diffs.claim(id, now)).isZero();       // 이미 PROCESSING → 재점유 불가
    }

    // 서비스 retryDiff: 존재하지 않는 쌍은 404.
    @Test
    void retryDiff_onMissingPair_throwsNotFound() {
        VersionInfo v1 = service.createInitialVersion(
                "alice", "/projects/retry.txt", FileContent.ofText("a\n", "text/plain"));
        assertThatThrownBy(() ->
                service.retryDiff("alice", v1.getFileId(), "no-from", "no-to"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // 서비스 retryDiff: 이미 COMPLETED면 재설정하지 않고 현재 상태를 그대로 돌려준다(무해).
    @Test
    void retryDiff_onCompleted_isNoOp() {
        VersionInfo v1 = service.createInitialVersion(
                "alice", "/projects/retry2.txt", FileContent.ofText("a\nb\n", "text/plain"));
        VersionInfo v2 = service.onDocumentModified(
                "alice", v1.getFileId(), FileContent.ofText("a\nB\nc\n", "text/plain"));
        worker.runOnce(10); // PENDING → COMPLETED

        Map<String, Object> after = service.retryDiff(
                "alice", v1.getFileId(), v1.getVersionId(), v2.getVersionId());
        assertThat(after.get("status")).isEqualTo("COMPLETED");
    }
}
