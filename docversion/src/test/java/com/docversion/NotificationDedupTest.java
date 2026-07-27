package com.docversion;

import com.docversion.domain.FileContent;
import com.docversion.domain.VersionInfo;
import com.docversion.service.DocumentLifecycleService;
import com.docversion.service.DocumentVersionService;
import com.docversion.service.NotificationService;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1d: 알림 중복 방지 키 회귀 테스트.
 *
 * <p>핵심 회귀: 과거에는 중복 판정 키에 <b>5분 시간 구간</b>이 들어 있어서, 같은 제목의 서로 다른
 * 사건이 5분 안에 연달아 일어나면 뒤엣것이 조용히 사라졌다. 테스트는 몇 밀리초 간격으로 실행되므로
 * 이 결함이 있으면 아래 테스트들이 곧바로 실패한다.
 *
 * <p>반대 방향(진짜 중복은 여전히 막히는지)도 함께 확인한다 — 중복 방지 자체를 없앤 것이 아니다.
 */
@SpringBootTest
@Testcontainers
class NotificationDedupTest {

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

    @Autowired DocumentVersionService versions;
    @Autowired DocumentLifecycleService lifecycle;
    @Autowired NotificationService notifications;

    /**
     * 핵심 회귀: 짧은 간격의 연속 수정이 각각 별개 알림으로 남는다.
     * (구식 5분 구간 키였다면 두 번째·세 번째가 유실되어 1건만 남았다.)
     */
    @Test
    void consecutiveVersionUpdates_eachProduceOwnNotification() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/dedup1.txt", FileContent.ofText("a\n", "text/plain"));
        String fileId = v1.getFileId();
        // bob을 구독자로 등록 — 알림 수신 대상 (alice는 행위자라 자신은 제외됨)
        notifications.subscribe(fileId, "bob");

        versions.onDocumentModified("alice", fileId, FileContent.ofText("b\n", "text/plain"));
        versions.onDocumentModified("alice", fileId, FileContent.ofText("c\n", "text/plain"));
        versions.onDocumentModified("alice", fileId, FileContent.ofText("d\n", "text/plain"));

        List<Map<String, Object>> got = notifications.listForUser("bob", false, 50);
        long newVersionNotifs = got.stream()
                .filter(x -> "새 버전".equals(x.get("subject")))
                .count();
        assertThat(newVersionNotifs)
                .as("연속 수정 3건은 각각 별개 사건이므로 알림도 3건이어야 한다")
                .isEqualTo(3);
    }

    /**
     * 상태 변경도 마찬가지. 같은 전이(검토중→초안)를 반복해도 매번 알림이 남아야 한다.
     * 이력 일련번호를 사건 식별자로 쓰므로 같은 전이라도 키가 달라진다.
     */
    @Test
    void repeatedSameTransition_eachProducesNotification() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/dedup2.txt", FileContent.ofText("a\n", "text/plain"));
        String fileId = v1.getFileId();
        notifications.subscribe(fileId, "bob");

        // 초안 → 검토중 → 초안 → 검토중 → 초안 : 같은 전이 쌍이 두 번 반복된다.
        lifecycle.changeStatus(fileId, "alice", "UNDER_REVIEW", null);
        lifecycle.changeStatus(fileId, "alice", "DRAFT", null);
        lifecycle.changeStatus(fileId, "alice", "UNDER_REVIEW", null);
        lifecycle.changeStatus(fileId, "alice", "DRAFT", null);

        long statusNotifs = notifications.listForUser("bob", false, 50).stream()
                .filter(x -> "상태 변경".equals(x.get("subject")))
                .count();
        assertThat(statusNotifs)
                .as("상태 전이 4건은 각각 별개 사건이므로 알림도 4건이어야 한다")
                .isEqualTo(4);
    }

    /**
     * 반대 방향 확인: 같은 사건이 두 번 적재되면 두 번째는 막혀야 한다.
     * (중복 방지를 없앤 것이 아니라, 판정 기준을 시간에서 사건으로 바꾼 것이다.)
     */
    @Test
    void sameEventKeyTwice_isDeduplicated() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/dedup3.txt", FileContent.ofText("a\n", "text/plain"));
        String fileId = v1.getFileId();
        notifications.subscribe(fileId, "carol");

        String sameEvent = "test:fixed-event-id";
        notifications.notifyUser("carol", fileId, sameEvent, "중복 시험", "첫 번째");
        notifications.notifyUser("carol", fileId, sameEvent, "중복 시험", "두 번째");

        long n = notifications.listForUser("carol", false, 50).stream()
                .filter(x -> "중복 시험".equals(x.get("subject")))
                .count();
        assertThat(n).as("같은 사건 식별자면 두 번째는 무시된다").isEqualTo(1);
    }

    /**
     * 같은 사건에서 브로드캐스트와 대상 지정 알림이 함께 나갈 때, 종류를 덧붙여 구분하면
     * 한 수신자가 둘 다 받는다. (구분하지 않으면 뒤엣것이 삼켜진다 — 순차 결재 첫 승인자가
     * "승인 요청"과 "승인 차례" 중 하나만 받게 되는 상황.)
     */
    @Test
    void sameEventDifferentKinds_bothDelivered() {
        VersionInfo v1 = versions.createInitialVersion(
                "alice", "/projects/dedup4.txt", FileContent.ofText("a\n", "text/plain"));
        String fileId = v1.getFileId();
        notifications.subscribe(fileId, "dave");

        String ev = "test:one-event";
        notifications.notifyUser("dave", fileId, ev + ":req", "요청 알림", "브로드캐스트 몫");
        notifications.notifyUser("dave", fileId, ev + ":turn", "차례 알림", "대상 지정 몫");

        List<Map<String, Object>> got = notifications.listForUser("dave", false, 50);
        assertThat(got.stream().filter(x -> "요청 알림".equals(x.get("subject"))).count()).isEqualTo(1);
        assertThat(got.stream().filter(x -> "차례 알림".equals(x.get("subject"))).count()).isEqualTo(1);
    }
}
