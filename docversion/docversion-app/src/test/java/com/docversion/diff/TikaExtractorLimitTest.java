package com.docversion.diff;

import com.docversion.domain.FileContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추출 길이 상한에 걸렸을 때의 동작. (RD-SRS-9.4 · 5.1 / F9)
 *
 * <p><b>종전 동작.</b> Tika는 길이 상한을 넘으면 예외를 던진다. 그 예외를 실패로 처리해
 * 이미 읽어낸 앞부분까지 함께 버렸고, 결과적으로 긴 문서는 통째로 <b>판정 불가</b>가 되었다.
 *
 * <p><b>왜 문제인가.</b> 탐지 엔진({@code RuleBasedScanner})은 같은 상황에서 앞부분만
 * 검사하고 "잘라서 검사했다"는 사실을 결과에 남긴다. 즉 같은 정책이 두 곳에서 다르게
 * 구현되어 있었다. 검사되지 않은 문서가 늘어나는 방향이므로 안전한 쪽으로 어긋난 것도 아니다.
 *
 * <p>지금은 앞부분을 살려 돌려준다. 자르는 일과 그 사실을 기록하는 일은 엔진 한 곳에서
 * 일어나며, 추출기의 상한(1,000만 자)은 엔진의 상한(500만 자)보다 높게 잡아
 * 정상 경로에서는 닿지 않는 안전 밸브로만 남는다.
 *
 * <p>스프링도 데이터베이스도 쓰지 않는다. 추출기 단독 시험이다.
 */
class TikaExtractorLimitTest {

    /** 상한을 넘지 않는 평범한 문서는 그대로 전부 추출된다. */
    @Test
    void documentUnderLimit_isExtractedInFull() {
        String body = "인사기록카드\n주민등록번호: 900101-1000006\n";
        DiffTypes.ExtractionResult r =
                new TikaDocumentTextExtractor(10_000).extractText(text(body));

        assertThat(r.success()).isTrue();
        assertThat(r.text()).contains("900101-1000006");
    }

    /**
     * 상한을 넘으면 실패가 아니라 <b>앞부분</b>을 돌려준다.
     *
     * <p>이 시험이 실패하면 긴 문서가 다시 판정 불가로 떨어진다는 뜻이다.
     */
    @Test
    void documentOverLimit_returnsLeadingPortion_notFailure() {
        int limit = 200;
        StringBuilder sb = new StringBuilder("주민등록번호: 900101-1000006\n");
        while (sb.length() < limit * 20) {
            sb.append("본문을 채우는 문단입니다. 내용상 의미는 없습니다.\n");
        }

        DiffTypes.ExtractionResult r =
                new TikaDocumentTextExtractor(limit).extractText(text(sb.toString()));

        assertThat(r.success())
                .as("상한 초과를 실패로 처리하면 문서 전체가 검사되지 않는다")
                .isTrue();
        assertThat(r.text())
                .as("앞부분은 살아 있어야 한다")
                .isNotBlank();
        assertThat(r.text().length())
                .as("상한 부근까지만 담긴다")
                .isLessThanOrEqualTo(limit + 100);
        assertThat(r.text())
                .as("문서 앞머리의 민감 정보는 여전히 검사 대상이 되어야 한다")
                .contains("900101-1000006");
    }

    /** 건질 내용이 없으면 종전대로 실패다. 빈 텍스트를 성공으로 돌려주면 안 된다. */
    @Test
    void unparseableContent_stillFails() {
        DiffTypes.ExtractionResult r = new TikaDocumentTextExtractor(10_000)
                .extractText(new FileContent(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}, "application/pdf"));

        assertThat(r.success())
                .as("빈 결과를 성공으로 돌려주면 '차이 없음'·'민감하지 않음'이라는 "
                        + "잘못된 결론으로 이어진다")
                .isFalse();
    }

    private static FileContent text(String s) {
        return FileContent.ofText(s, "text/plain");
    }
}
