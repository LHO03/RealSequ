package com.example.docver;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 모든 통합 테스트의 공통 베이스.
 *
 * 책임:
 *   1. MariaDB Testcontainer를 정적으로 공유 (모든 테스트 클래스가 같은 인스턴스 사용 → 빠름)
 *   2. Spring DataSource를 컨테이너 URL로 동적 주입
 *   3. 각 테스트 전 모든 테이블 데이터 정리 (테스트 간 격리)
 *
 * 사용:
 *   class MyTest extends IntegrationTestBase {
 *       @Test void something() { ... }
 *   }
 *
 * 컨테이너는 JVM 종료 시까지 살아있음 (Testcontainers의 "reusable singleton" 패턴).
 */
@SpringBootTest
@Testcontainers
@ContextConfiguration(initializers = IntegrationTestBase.ContainerInitializer.class)
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // static으로 선언하여 JVM 전체에서 1개 컨테이너만 사용
    // @Container 어노테이션 대신 수동 start로 라이프사이클 명시 관리
    protected static final MariaDBContainer<?> MARIADB;

    static {
        MARIADB = new MariaDBContainer<>("mariadb:10.11")
                .withDatabaseName("docver_test")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);  // Testcontainers reuse 기능 (~/.testcontainers.properties)
        MARIADB.start();
    }

    /**
     * Spring DataSource URL을 컨테이너의 동적 포트로 설정.
     * 컨테이너 시작 후에야 포트가 결정되므로 ApplicationContext 초기화 시점에 주입.
     */
    public static class ContainerInitializer implements
            ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertyValues.of(
                    "spring.datasource.url=" + MARIADB.getJdbcUrl(),
                    "spring.datasource.username=" + MARIADB.getUsername(),
                    "spring.datasource.password=" + MARIADB.getPassword(),
                    "spring.datasource.driver-class-name=" + MARIADB.getDriverClassName(),
                    // schema.sql/seed.sql은 application.yml의 spring.sql.init 설정으로 자동 실행
                    "spring.sql.init.mode=always"
            ).applyTo(ctx.getEnvironment());
        }
    }

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * 각 테스트 전 모든 테이블 데이터 정리.
     * schema.sql의 DROP TABLE IF EXISTS는 한 번만 실행되므로,
     * 매 테스트마다 TRUNCATE로 데이터만 초기화 (스키마는 유지).
     *
     * FK 의존성 역순으로 정리:
     *   notifications → approval_activity → approval_rule_* → approval_rules
     *   → systemtag_object_mapping → systemtag → activity → files_versions
     * systemtag은 시드 데이터(5개 status tag) 보존을 위해 정리 후 재삽입.
     */
    @BeforeEach
    void cleanData() {
        // FK 체크 일시 해제 후 일괄 정리
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String table : new String[]{
                "notifications",
                "approval_activity",
                "approval_rule_approvers",
                "approval_rule_requesters",
                "approval_rules",
                "systemtag_object_mapping",
                "activity",
                "files_versions"
        }) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        // systemtag은 시드 데이터 보존을 위해 DELETE → 재INSERT (TRUNCATE 시 FK 영향)
        jdbc.execute("DELETE FROM systemtag");
        jdbc.update(
                "INSERT INTO systemtag (id, name, visibility, editable) VALUES " +
                "('tag_draft', 'draft', 1, 1), " +
                "('tag_under_review', 'under_review', 1, 1), " +
                "('tag_approved', 'approved', 1, 1), " +
                "('tag_rejected', 'rejected', 1, 1), " +
                "('tag_deprecated', 'deprecated', 1, 1)"
        );
        jdbc.execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}