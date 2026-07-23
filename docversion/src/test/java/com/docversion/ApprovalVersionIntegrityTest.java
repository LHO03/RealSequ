package com.docversion;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.mapper.DocumentMapper;
import com.docversion.service.ApprovalService;
import com.docversion.service.DocumentLifecycleService;
import com.docversion.service.DocumentVersionService;
import com.docversion.service.ForbiddenOperationException;
import com.docversion.service.InvalidRequestException;
import com.docversion.service.ModificationBlockedException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 클러스터 1 정합성 코어 회귀 테스트 (V11, 정책 A).
 *
 * <p>확립하는 불변조건:
 * "승인 판정은 항상 검토된 그 버전에 적용되고, 문서는 승인 워크플로를 통해서만 APPROVED가 되며,
 *  승인된 문서를 수정하면 다시 REVISION_DRAFT로 돌아간다."
 *
 * <p>실제 MariaDB(Testcontainers)에서 돌려 FOR UPDATE·트랜잭션 경계·상태 전이가 함께 동작하는지 본다.
 */
@SpringBootTest
@Testcontainers
class ApprovalVersionIntegrityTest {

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
    @Autowired DocumentLifecycleService lifecycle;
    @Autowired ApprovalService approvals;
    @Autowired DocumentMapper documents;

    /** 검토중 상태로 만들고 bob에게 승인 요청을 낸 문서의 fileId를 돌려준다. */
    private VersionInfo openReview(String path) {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", path, FileContent.ofText("초안 내용\n", "text/plain"));
        lifecycle.changeStatus(v1.getFileId(), "alice", "UNDER_REVIEW", "검토 요청");
        approvals.request(v1.getFileId(), "alice", List.of("bob"), "ALL", "검토 부탁드립니다");
        return v1;
    }

    // 1) 열린 승인 요청 진행 중 새 버전 업로드 → 거부(409). 요청·상태는 그대로.
    @Test
    void upload_whileApprovalOpen_isBlocked_andReviewUntouched() {
        VersionInfo v1 = openReview("/projects/spec1.txt");

        assertThatThrownBy(() -> versions.onDocumentModified(
                "alice", v1.getFileId(), FileContent.ofText("몰래 수정\n", "text/plain")))
                .isInstanceOf(ModificationBlockedException.class)
                .hasMessageContaining("승인 요청");

        // 열린 요청 그대로, 상태도 검토중 유지 (검토 대상이 바뀌지 않았다)
        assertThat(approvals.getState(v1.getFileId()).open()).isNotNull();
        assertThat(lifecycle.getStatus(v1.getFileId()).status()).isEqualTo("UNDER_REVIEW");
    }

    // 2) APPROVED 문서에 새 버전 업로드 → 성공 + 상태 REVISION_DRAFT.
    @Test
    void modifyingApprovedDocument_resetsToRevisionDraft() {
        VersionInfo v1 = openReview("/projects/spec2.txt");
        approvals.approve(v1.getFileId(), "bob", "승인합니다"); // ALL + 승인자 1명 → 즉시 APPROVED
        assertThat(lifecycle.getStatus(v1.getFileId()).status()).isEqualTo("APPROVED");

        // 승인 완료(열린 요청 없음) 후 새 버전 업로드는 허용되고, 상태가 되돌아간다.
        VersionInfo v2 = versions.onDocumentModified(
                "alice", v1.getFileId(), FileContent.ofText("승인 후 수정\n", "text/plain"));

        assertThat(v2.getRevisionNo()).isEqualTo(2);
        assertThat(lifecycle.getStatus(v1.getFileId()).status()).isEqualTo("REVISION_DRAFT");
    }

    // 3) 정상 승인 → 승인 대상 버전이 요청 생성 시점의 버전으로 고정되고, 문서는 APPROVED.
    @Test
    void approvalRequest_pinsTargetVersion_andApproves() {
        VersionInfo v1 = openReview("/projects/spec3.txt");

        // 요청에 고정된 대상 버전 == 최초 버전
        Object target = approvals.getState(v1.getFileId()).open().get("targetVersionId");
        assertThat(target).isEqualTo(v1.getVersionId());

        approvals.approve(v1.getFileId(), "bob", "확인 완료");
        assertThat(lifecycle.getStatus(v1.getFileId()).status()).isEqualTo("APPROVED");
    }

    // 4) 이중 방어: 승인 확정 직전 대상≠현재 버전이면 승인하지 않고 요청을 STALE로 종료.
    //    (정책 A 하에선 정상 경로로 발생 불가 → current_version_id를 직접 틀어 이상 상황을 재현한다.)
    @Test
    void finalApproval_withStaleTargetVersion_closesAsStale_notApproved() {
        VersionInfo v1 = openReview("/projects/spec4.txt");

        // 열린 요청이 v1을 대상으로 고정한 뒤, 현재 버전 포인터를 다른 값으로 오염시킨다(이상 상황 모사).
        documents.updateLivePointer(v1.getFileId(),
                "ffffffff-ffff-ffff-ffff-ffffffffffff", 99L,
                System.currentTimeMillis() / 1000);

        approvals.approve(v1.getFileId(), "bob", "승인 시도");

        // 승인되지 않았다: 문서는 APPROVED가 아니고(검토중 유지), 열린 요청은 닫혔다(STALE).
        assertThat(lifecycle.getStatus(v1.getFileId()).status()).isNotEqualTo("APPROVED");
        assertThat(approvals.getState(v1.getFileId()).open()).isNull();
    }

    // 5) 수동 상태 API로 UNDER_REVIEW → APPROVED 자가 승인 시도 → 거부(403).
    @Test
    void manualStatusChange_toApproved_isForbidden() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/spec5.txt", FileContent.ofText("본문\n", "text/plain"));
        lifecycle.changeStatus(v1.getFileId(), "alice", "UNDER_REVIEW", "제출");

        assertThatThrownBy(() ->
                lifecycle.changeStatus(v1.getFileId(), "alice", "APPROVED", "그냥 승인"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessageContaining("승인 워크플로");

        assertThat(lifecycle.getStatus(v1.getFileId()).status()).isEqualTo("UNDER_REVIEW");
    }

    // 6) P1 예외 분리: 잘못된 판정 방식은 "잘못된 입력"(→400)이지 "없음"(404)이 아니다.
    @Test
    void approvalRequest_withInvalidMode_isInvalidRequest() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/spec6.txt", FileContent.ofText("본문\n", "text/plain"));
        lifecycle.changeStatus(v1.getFileId(), "alice", "UNDER_REVIEW", "제출");

        assertThatThrownBy(() ->
                approvals.request(v1.getFileId(), "alice", List.of("bob"), "WRONG_MODE", null))
                .isInstanceOf(InvalidRequestException.class);
    }

    // 7) P1: 승인자 0명도 잘못된 입력(→400).
    @Test
    void approvalRequest_withNoApprovers_isInvalidRequest() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/spec7.txt", FileContent.ofText("본문\n", "text/plain"));
        lifecycle.changeStatus(v1.getFileId(), "alice", "UNDER_REVIEW", "제출");

        assertThatThrownBy(() ->
                approvals.request(v1.getFileId(), "alice", List.of(), "ALL", null))
                .isInstanceOf(InvalidRequestException.class);
    }

    // 8) P1: 없는 문서에 대한 요청은 "없음"(→404).
    @Test
    void approvalRequest_onMissingDocument_isResourceNotFound() {
        assertThatThrownBy(() ->
                approvals.request("no-such-file", "alice", List.of("bob"), "ALL", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // 9) P1: 알 수 없는 상태 문자열은 잘못된 입력 → 원시 IllegalArgumentException(어드바이스에서 400).
    @Test
    void manualStatusChange_toUnknownStatus_isIllegalArgument() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/spec9.txt", FileContent.ofText("본문\n", "text/plain"));

        assertThatThrownBy(() ->
                lifecycle.changeStatus(v1.getFileId(), "alice", "NONSENSE", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
