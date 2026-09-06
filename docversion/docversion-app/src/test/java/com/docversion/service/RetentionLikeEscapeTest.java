package com.docversion.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보존 정책 FOLDER 범위의 LIKE 와일드카드 이스케이프. (RD-SRS-9.10 / F16)
 *
 * <p><b>왜 중요한가.</b> FOLDER 범위는 경로 접두사로 정리 대상 문서를 고른다.
 * 접두사가 LIKE 패턴에 그대로 들어가면 {@code %}와 {@code _}가 와일드카드로 해석된다.
 * 보존 정책은 <b>버전을 삭제하는 기능</b>이므로, 대상이 넓게 잡히는 것은 곧
 * 의도하지 않은 삭제다. 범위를 {@code %} 하나로 주면 전체 문서가 대상이 된다.
 *
 * <p>스프링도 데이터베이스도 쓰지 않는다. 이스케이프 규칙만 확인하되,
 * 매칭은 MariaDB의 {@code LIKE ... ESCAPE '\'} 동작을 모사해 <b>실제로 걸러지는지</b>까지 본다.
 * 이스케이프 문자열만 비교하면 "규칙은 맞는데 효과가 없는" 경우를 놓친다.
 */
class RetentionLikeEscapeTest {

    /**
     * {@code LIKE <패턴> ESCAPE '\'} 를 모사한다.
     *
     * <p>매퍼의 SQL이 {@code CONCAT(#{prefix}, '%')} 이므로 접두사 뒤에 {@code %}를 붙인다.
     */
    private static boolean likeMatches(String path, String escapedPrefix) {
        String pattern = escapedPrefix + "%";
        StringBuilder rx = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                rx.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));  // 이스케이프된 리터럴
            } else if (c == '%') {
                rx.append(".*");
            } else if (c == '_') {
                rx.append(".");
            } else {
                rx.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return path.matches(rx.toString());
    }

    private static boolean selects(String scopeId, String path) {
        return likeMatches(path, RetentionPolicyService.escapeLikePrefix(scopeId));
    }

    // ------------------------------------------------------------
    // 본래 기능은 그대로여야 한다
    // ------------------------------------------------------------

    @Test
    @DisplayName("평범한 폴더 접두사는 종전대로 동작한다")
    void ordinaryPrefixStillWorks() {
        assertThat(selects("/legal", "/legal/contract.docx")).isTrue();
        assertThat(selects("/legal", "/legal")).isTrue();
        assertThat(selects("/legal", "/hr/memo.docx"))
                .as("다른 폴더까지 잡으면 안 된다")
                .isFalse();
    }

    // ------------------------------------------------------------
    // 여기가 F16 — 와일드카드가 무력화되는가
    // ------------------------------------------------------------

    @Test
    @DisplayName("F16 범위에 %만 넣어도 전체 문서가 대상이 되지 않는다")
    void percentDoesNotSelectEverything() {
        assertThat(selects("%", "/anything/at/all.txt"))
                .as("종전에는 이 한 글자로 시스템 전체 문서가 정리 대상이 되었다")
                .isFalse();
    }

    @Test
    @DisplayName("F16 접두사에 섞인 %가 임의 문자열로 해석되지 않는다")
    void percentInsidePrefixIsLiteral() {
        assertThat(selects("/legal%", "/hr/other.docx"))
                .as("종전에는 '/legal'로 시작하지 않는 문서까지 걸렸다")
                .isFalse();
        assertThat(selects("/legal%", "/legal%/x.docx"))
                .as("리터럴 '%'가 실제로 들어간 경로는 여전히 일치해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("F16 밑줄이 임의의 한 글자로 해석되지 않는다")
    void underscoreIsLiteral() {
        assertThat(selects("/le_al", "/legal/x.docx"))
                .as("종전에는 '_'가 임의 1글자라 /legal 이 걸렸다")
                .isFalse();
        assertThat(selects("/le_al", "/le_al/x.docx"))
                .as("리터럴 밑줄 경로는 일치해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("역슬래시가 든 경로도 정상 처리된다")
    void backslashInPathIsHandled() {
        // 역슬래시를 먼저 치환하지 않으면 뒤이어 넣는 이스케이프 문자가 다시 이스케이프되어
        // 규칙이 깨진다. 순서를 바꾸면 이 시험이 실패한다.
        assertThat(selects("/a\\b", "/a\\b/c.docx")).isTrue();
        assertThat(selects("/a\\b", "/axb/c.docx")).isFalse();
    }

    @Test
    @DisplayName("null은 그대로 통과시킨다")
    void nullIsPassedThrough() {
        assertThat(RetentionPolicyService.escapeLikePrefix(null)).isNull();
    }
}
