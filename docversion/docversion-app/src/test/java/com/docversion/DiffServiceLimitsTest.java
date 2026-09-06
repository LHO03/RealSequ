package com.docversion;

import com.docversion.diff.DiffService;
import com.docversion.diff.DiffTypes.DiffMethod;
import com.docversion.diff.DiffTypes.DiffResult;
import com.docversion.diff.NoopDocumentTextExtractor;
import com.docversion.domain.FileContent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 버전 비교의 규모 한계 회귀 시험. (RD-SRS-9.4 / F1)
 *
 * <p>스프링도 데이터베이스도 쓰지 않는다. 순수 계산이므로 도커 없이 바로 돌아간다.
 *
 * <p><b>무엇을 지키려는 시험인가.</b> Myers 알고리즘은 탐색 상태 배열을 단계마다
 * 복제해 보관한다. 종전 구현은 그 배열을 <b>파일 전체 줄 수</b> 기준으로 잡아,
 * 편집 거리가 작아도 파일이 크면 메모리를 크게 썼다. 실측으로 5만 줄 문서에서
 * 500줄을 수정했을 때 힙이 1GB 늘었고, 3,000줄을 수정하면 힙 2GB에서
 * OutOfMemoryError가 났다.
 *
 * <p>배열을 편집 거리 기준으로 잡으면 같은 계산이 수십 분의 1의 메모리로 끝난다.
 * 그 위에 두 개의 상한을 두어, 상한을 넘는 입력에서는 죽는 대신 해시 비교로 내려간다.
 *
 * <p>가장 중요한 것은 마지막 시험이다. 상한에 걸렸을 때 <b>조용히 "차이 없음"으로
 * 보고하지 않는지</b>를 본다. 비교 결과가 틀리는 것은 비교에 실패하는 것보다 나쁘다.
 */
class DiffServiceLimitsTest {

    /** 상한을 인자로 받아 시험마다 다른 규모를 만들지 않아도 되게 한다. */
    private static DiffService service(int maxTotalLines, int maxEditDistance) {
        return new DiffService(new NoopDocumentTextExtractor(), maxTotalLines, maxEditDistance);
    }

    private static FileContent lines(String prefix, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(prefix).append(' ').append(i).append('\n');
        }
        return FileContent.ofText(sb.toString(), "text/plain");
    }

    // ------------------------------------------------------------
    // 1) 평범한 수정은 종전과 똑같이 줄 단위로 비교된다
    // ------------------------------------------------------------

    @Test
    void ordinaryEdit_stillProducesLineLevelDiff() {
        DiffService diff = service(200_000, 7_000);

        DiffResult r = diff.computeDiff(
                FileContent.ofText("첫째 줄\n둘째 줄\n셋째 줄\n", "text/plain"),
                FileContent.ofText("첫째 줄\n바뀐 줄\n셋째 줄\n", "text/plain"));

        assertThat(r.method)
                .as("평문 텍스트는 줄 단위 비교 경로를 탄다")
                .isEqualTo(DiffMethod.TEXT_DIRECT);
        assertThat(r.addedLines).isEqualTo(1);
        assertThat(r.deletedLines).isEqualTo(1);
        assertThat(r.unifiedDiff).contains("바뀐 줄");
    }

    // ------------------------------------------------------------
    // 2) 큰 파일 + 작은 수정 — 종전에 힙 1GB를 쓰던 형태
    // ------------------------------------------------------------

    @Test
    void largeFileWithSmallEdit_completesWithinModestMemory() {
        DiffService diff = service(200_000, 7_000);

        int total = 20_000;
        int modified = 200;

        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < total; i++) {
            before.append("본문 줄 ").append(i).append('\n');
            after.append(i < modified ? "수정된 줄 " : "본문 줄 ").append(i).append('\n');
        }

        DiffResult r = diff.computeDiff(
                FileContent.ofText(before.toString(), "text/plain"),
                FileContent.ofText(after.toString(), "text/plain"));

        assertThat(r.method)
                .as("편집 거리가 상한 안이면 줄 단위 비교가 끝까지 수행되어야 한다")
                .isEqualTo(DiffMethod.TEXT_DIRECT);
        assertThat(r.addedLines)
                .as("수정된 줄 수만큼 추가로 잡혀야 한다")
                .isEqualTo(modified);
        assertThat(r.deletedLines).isEqualTo(modified);
    }

    // ------------------------------------------------------------
    // 2-b) 리뷰에서 OutOfMemoryError가 났던 바로 그 형태
    // ------------------------------------------------------------

    /**
     * 5만 줄 문서에서 3,000줄 수정. 코드 정독 당시 힙 2GB로도
     * {@code OutOfMemoryError}가 났던 입력이다.
     *
     * <p>편집 거리는 6,000(삭제 3,000 + 삽입 3,000)으로 기본 상한 7,000 안에 들어온다.
     * 배열을 편집 거리 기준으로 잡고 단계마다 유효 구간만 보관하면 약 150MB로 완주한다.
     *
     * <p>이 시험이 상한 부족으로 HASH_ONLY가 되기 시작한다면, 그것은 회귀가 아니라
     * {@code docversion.diff.max-edit-distance}를 낮춘 결과다. 메모리는 이 값의
     * 제곱으로 늘어나므로 올릴 때는 그 점을 감안해야 한다.
     */
    @Test
    void fiftyThousandLinesWithThreeThousandEdits_completes_wasOutOfMemoryBefore() {
        DiffService diff = service(200_000, 7_000);

        int total = 50_000;
        int modified = 3_000;

        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < total; i++) {
            before.append("line ").append(i).append('\n');
            after.append(i < modified ? "CHANGED " : "line ").append(i).append('\n');
        }

        DiffResult r = diff.computeDiff(
                FileContent.ofText(before.toString(), "text/plain"),
                FileContent.ofText(after.toString(), "text/plain"));

        assertThat(r.method)
                .as("종전에는 여기서 OutOfMemoryError가 났고, 그 Error가 catch(Exception)을 "
                        + "통과해 작업자를 무한 재시도에 빠뜨렸다")
                .isEqualTo(DiffMethod.TEXT_DIRECT);
        assertThat(r.addedLines).isEqualTo(modified);
        assertThat(r.deletedLines).isEqualTo(modified);
    }

    // ------------------------------------------------------------
    // 3) 줄 수 상한 — 시도 자체를 하지 않고 해시 비교로 내려간다
    // ------------------------------------------------------------

    @Test
    void tooManyLines_degradesToHashOnly() {
        // 줄 수 상한을 낮게 잡아 큰 파일을 만들지 않고 같은 경로를 태운다.
        DiffService diff = service(100, 4_000);

        DiffResult r = diff.computeDiff(lines("이전", 200), lines("이후", 200));

        assertThat(r.method)
                .as("상한을 넘으면 줄 단위 비교를 시도하지 않는다")
                .isEqualTo(DiffMethod.HASH_ONLY);
        assertThat(r.summary)
                .as("왜 생략했는지 숫자와 함께 남아야 설정을 조정할지 판단할 수 있다")
                .contains("줄 단위 비교는 생략")
                .contains("400");
    }

    // ------------------------------------------------------------
    // 4) 편집 거리 상한 — 여기서 조용히 "차이 없음"이 나오면 안 된다
    // ------------------------------------------------------------

    @Test
    void editDistanceLimitReached_reportsDifference_neverSilentlyEqual() {
        // 완전히 다른 두 파일(편집 거리 = 400)을 상한 10으로 비교한다.
        DiffService diff = service(200_000, 10);

        DiffResult r = diff.computeDiff(lines("이전", 200), lines("이후", 200));

        assertThat(r.method)
                .as("상한 안에서 경로를 찾지 못하면 해시 비교로 내려간다")
                .isEqualTo(DiffMethod.HASH_ONLY);

        // 여기가 이 시험의 핵심이다.
        // 상한 검사가 없으면 역추적 루프가 한 번도 돌지 않은 채 모든 줄이 EQUAL로 채워져
        // "차이 없음"이라는 정반대의 결과가 조용히 나온다.
        assertThat(r.summary)
                .as("내용이 달라졌다는 사실 자체는 반드시 전달되어야 한다")
                .contains("내용이 달라졌으나");
        assertThat(r.summary)
                .as("'차이 없음'으로 보고되면 비교 기능이 있으나 마나가 된다")
                .doesNotContain("No changes");
        assertThat(r.oldHash)
                .as("해시는 여전히 유효한 정보다")
                .isNotEqualTo(r.newHash);
    }

    // ------------------------------------------------------------
    // 5) 내용이 같으면 상한과 무관하게 '변경 없음'이다
    // ------------------------------------------------------------

    @Test
    void identicalContent_isStillReportedAsNoChange_regardlessOfLimits() {
        DiffService diff = service(10, 1);

        FileContent same = lines("본문", 500);
        DiffResult r = diff.computeDiff(same, same);

        assertThat(r.summary)
                .as("해시 동일성 판별이 규모 한계보다 먼저 오므로 상한에 걸리지 않는다")
                .isEqualTo("No changes detected");
        assertThat(r.addedLines).isZero();
        assertThat(r.deletedLines).isZero();
    }
}
