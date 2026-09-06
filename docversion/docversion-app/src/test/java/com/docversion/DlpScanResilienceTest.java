package com.docversion;

import com.docversion.dlp.DlpScanWorker;
import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.mapper.DlpScanMapper;
import com.docversion.mapper.FilesVersionMapper;
import com.docversion.service.DocumentVersionService;
import com.docversion.text.VersionTextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * DLP 검사 작업자의 실패 처리 회귀 시험. (RD-SRS-5.1)
 *
 * <p>대량 문서를 연속으로 검사하는 상황(탐지 정확도 측정 등)을 전제로,
 * 한 건의 실패가 서버 전체나 이후 재검사를 막지 않는지 확인한다.
 *
 * <p>두 가지를 다룬다.
 * <ol>
 *   <li><b>치명적 오류의 즉시 확정</b> — {@code OutOfMemoryError} 같은 Error 계열은
 *       {@code catch (Exception)}에 잡히지 않는다. 그 결과 작업이 PROCESSING에 남고
 *       stale 회수가 120초마다 다시 집어 같은 실패를 무한히 반복했다.</li>
 *   <li><b>추출 상태 고착 해소</b> — 텍스트 추출에 한 번 실패한 버전은 재검사를
 *       요청해도 계속 판정 불가로 남았다.</li>
 * </ol>
 *
 * <p>{@code @SpyBean}을 쓰는 이유: 첫 시험은 추출 단계에서 Error를 던져야 하고,
 * 둘째 시험은 실제 추출 동작이 필요하다. 스파이는 스텁을 걸지 않으면 실제 객체로
 * 동작하며, 스프링이 시험 메서드마다 스텁을 초기화하므로 한 컨텍스트에서 둘 다 된다.
 */
@SpringBootTest
@Testcontainers
class DlpScanResilienceTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10.11")
            .withDatabaseName("nextcloud")
            .withUsername("nextcloud")
            .withPassword("nextcloud");

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

    @Autowired DocumentVersionService documents;
    @Autowired DlpScanWorker worker;
    @Autowired DlpScanMapper scans;
    @Autowired FilesVersionMapper filesVersions;

    /** 스텁을 걸지 않으면 실제 서비스로 동작한다. 시험 메서드마다 스텁은 초기화된다. */
    @SpyBean VersionTextService versionText;

    // ------------------------------------------------------------
    // 1) 치명적 오류는 재시도 없이 FAILED로 확정된다
    // ------------------------------------------------------------

    @Test
    void fatalError_isMarkedFailedImmediately_withoutRetry() {
        VersionInfo v = documents.createInitialVersion(
                "alice", "/dlp/fatal.txt", FileContent.ofText("본문\n", "text/plain"));
        String versionId = v.getVersionId();

        // 버전 생성 리스너가 FULL 검사를 적재한다 — 그것이 이 시험의 대상이다.
        assertThat(scans.findByVersionAndScope(versionId, "FULL"))
                .as("전제: 버전 생성 시 FULL 검사가 적재된다")
                .isNotNull();

        // 텍스트 확보 단계에서 Error를 던지게 한다.
        // catch(Exception)만 있던 시절에는 이것이 그대로 빠져나가 작업이 PROCESSING에 남았다.
        doThrow(new OutOfMemoryError("시험용 강제 오류"))
                .when(versionText).getOrExtract(anyString(), any());

        // 같은 클래스의 다른 시험이 남긴 작업이 먼저 잡힐 수 있으므로,
        // 이 버전의 작업이 PENDING을 벗어날 때까지 돌린다(실행 순서에 의존하지 않게).
        for (int i = 0; i < 5; i++) {
            Map<String, Object> s = scans.findByVersionAndScope(versionId, "FULL");
            if (!"PENDING".equals(s.get("status"))) {
                break;
            }
            worker.runOnce(10);
        }

        Map<String, Object> scan = scans.findByVersionAndScope(versionId, "FULL");

        assertThat(scan.get("status"))
                .as("Error는 재시도해도 결과가 같으므로 즉시 확정되어야 한다. "
                        + "PENDING이면 stale 회수가 무한히 반복하던 과거 동작이다")
                .isEqualTo("FAILED");

        assertThat(((Number) scan.get("attempts")).intValue())
                .as("한 번만 시도하고 끝나야 한다 (재시도 한도 3회를 소모하지 않는다)")
                .isEqualTo(1);

        assertThat(String.valueOf(scan.get("lastError")))
                .as("무엇 때문에 확정 실패했는지 기록에 남아야 한다")
                .contains("OutOfMemoryError");
    }

    // ------------------------------------------------------------
    // 2) 추출 상태 고착이 재검사로 풀린다
    // ------------------------------------------------------------

    @Test
    void stuckTextStatus_isResettable_soRescanCanReextract() {
        VersionInfo v = documents.createInitialVersion(
                "alice", "/dlp/stuck.txt", FileContent.ofText("주민등록번호 010203-4567890\n", "text/plain"));
        String versionId = v.getVersionId();
        FileContent content = FileContent.ofText("주민등록번호 010203-4567890\n", "text/plain");

        // 추출에 실패한 상태를 만든다 (형식 미지원·손상 파일·추출기 오류 상황을 모사)
        filesVersions.updateTextFailed(versionId, VersionTextService.FAILED,
                "시험용 강제 실패", Instant.now().getEpochSecond());

        // 고착 확인 — 재추출을 시도하지 않고 즉시 포기한다.
        // 이 상태에서는 재검사를 몇 번을 요청해도 계속 판정 불가로 남았다.
        assertThat(versionText.getOrExtract(versionId, content))
                .as("전제: FAILED 상태에서는 재추출하지 않는다")
                .isNull();

        // 재검사 요청이 하는 일: 고착 상태를 되돌린다
        assertThat(filesVersions.resetTextStatusIfUnresolved(versionId))
                .as("FAILED는 되돌릴 대상이다")
                .isEqualTo(1);

        // 이제 다시 추출된다
        assertThat(versionText.getOrExtract(versionId, content))
                .as("되돌린 뒤에는 추출이 다시 시도되어야 한다")
                .isNotBlank();

        // 이미 성공한 버전은 건드리지 않는다 — 캐시된 텍스트를 불필요하게 버리지 않는다
        assertThat(filesVersions.resetTextStatusIfUnresolved(versionId))
                .as("EXTRACTED는 되돌릴 대상이 아니다")
                .isZero();
    }

    // ------------------------------------------------------------
    // 3) 비교 결과를 기다리는 변경분 검사가 영원히 돌지 않는다 (F7)
    // ------------------------------------------------------------

    /**
     * 변경분 검사(DELTA)는 9.4 비교가 산출한 unified diff를 입력으로 쓴다.
     * 대응하는 비교 결과가 COMPLETED로 존재하지 않으면 검사할 대상이 없다.
     *
     * <p>종전에는 그 경우 무조건 PENDING으로 되돌렸다. 되돌리는 것 자체는 옳다 —
     * 비교가 아직 진행 중일 수 있기 때문이다. 문제는 <b>종료 조건이 없었다</b>는 점이다.
     * 비교 결과가 영영 나오지 않으면 검사 작업이 15초마다 무한히 되돌려진다.
     *
     * <p>도달 경로는 재검사 API에 있다. 비교가 없는 버전(최초 버전 등)에
     * {@code scope=DELTA}로 재검사를 요청하면 대응하는 비교 작업이 아예 생기지 않는다.
     * 여기서는 그 상황을 직접 적재해 재현한다.
     */
    @Test
    void deltaScanWithoutDiff_eventuallyStops_asUndetermined() {
        VersionInfo v = documents.createInitialVersion(
                "alice", "/dlp/delta-orphan.txt", FileContent.ofText("본문\n", "text/plain"));
        String versionId = v.getVersionId();

        // 최초 버전이므로 비교 대상이 없다 — version_diffs에 이 버전을 대상으로 한
        // COMPLETED 행이 존재하지 않는다. 그 상태에서 변경분 검사를 적재한다.
        scans.insertPending(v.getFileId(), versionId, "DELTA", Instant.now().getEpochSecond());

        assertThat(scans.findByVersionAndScope(versionId, "DELTA"))
                .as("전제: 변경분 검사가 적재되었다")
                .isNotNull();

        // 대기 한도(20주기)를 넘기도록 충분히 돌린다.
        // 종전 구현이라면 몇 번을 돌려도 PENDING을 벗어나지 못한다.
        Map<String, Object> scan = null;
        for (int i = 0; i < 30; i++) {
            // 다른 시험이 남긴 작업에 밀려 이 작업이 배치에서 빠지지 않도록 넉넉히 잡는다.
            worker.runOnce(50);
            scan = scans.findByVersionAndScope(versionId, "DELTA");
            if (!"PENDING".equals(scan.get("status"))) {
                break;
            }
        }

        assertThat(scan.get("status"))
                .as("종료 조건이 없으면 여기서 영원히 PENDING이다")
                .isEqualTo("COMPLETED");

        assertThat(scan.get("verdict"))
                .as("검사하지 못한 문서를 '민감하지 않음'으로 표시하면 유출 차단이 무력화된다. "
                        + "확보하지 못한 것은 판정 불가로 남아야 한다")
                .isEqualTo("UNDETERMINED");
    }
}
