package com.example.docver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers MariaDB + schema.sql + seed.sql이 정상 동작하는지 검증.
 *
 * 다른 통합 테스트가 실패할 때 원인 추적용 — 컨테이너 문제인지 코드 문제인지 분리.
 */
@DisplayName("컨테이너 / 스키마 셋업 검증")
class ContainerSetupSmokeTest extends IntegrationTestBase {

    @Test
    @DisplayName("MariaDB 컨테이너가 떴고 9개 테이블 모두 생성됨")
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = DATABASE()",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "files_versions",
                "activity",
                "systemtag",
                "systemtag_object_mapping",
                "approval_rules",
                "approval_rule_requesters",
                "approval_rule_approvers",
                "approval_activity",
                "notifications"
        );
    }

    @Test
    @DisplayName("seed.sql 또는 cleanData()로 5개 status 태그가 등록되어 있음")
    void statusTagsAreSeeded() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name FROM systemtag ORDER BY name");

        assertThat(rows).hasSize(5);
        assertThat(rows).extracting(r -> r.get("name").toString())
                .containsExactly("approved", "deprecated", "draft", "rejected", "under_review");
    }

    @Test
    @DisplayName("cleanData()가 다른 테이블 데이터를 깨끗이 정리함")
    void otherTablesAreEmptyBeforeTest() {
        // @BeforeEach cleanData가 호출된 후 상태
        for (String table : new String[]{
                "files_versions", "activity",
                "approval_rules", "approval_rule_requesters", "approval_rule_approvers",
                "approval_activity", "notifications", "systemtag_object_mapping"
        }) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table, Integer.class);
            assertThat(count)
                    .as("Table %s should be empty before each test", table)
                    .isZero();
        }
    }
}
