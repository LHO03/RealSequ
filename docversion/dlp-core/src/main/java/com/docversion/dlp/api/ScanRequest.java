package com.docversion.dlp.api;

import java.util.Objects;

/**
 * 검사 요청. (RD-SRS-5.1 / 5.2)
 *
 * <p>입력이 문서 바이트가 아니라 <b>추출된 텍스트</b>인 점이 이 계약의 핵심이다.
 * 형식별 텍스트 추출은 9.x가 이미 보유한 계층(DocumentTextExtractor)이 담당하고,
 * 탐지 엔진은 텍스트만 받는다. 이렇게 두는 이유는 세 가지다.
 *
 * <ul>
 *   <li>탐지 엔진이 문서 형식을 알 필요가 없어진다. 선행 산출물은 바이트를 그대로
 *       문자열로 변환했는데, docx·xlsx·pdf는 압축된 내부 구조라 본문 문자열이
 *       원형대로 존재하지 않아 정규식이 아무것도 잡지 못했다.</li>
 *   <li>추출 결과를 버전 비교(9.4)와 함께 재사용할 수 있다. 같은 문서를 두 번
 *       파싱하지 않는다.</li>
 *   <li>문자 인코딩 문제가 이 계층에서 사라진다. 추출기가 이미 String을 넘긴다.</li>
 * </ul>
 *
 * <p>{@code mimeType}은 탐지에 직접 쓰이지 않으나, 향후 형식별 규칙 분기나
 * 광학 문자 인식 결과 구분을 위해 참고용으로 전달한다.
 *
 * @param fileId    문서 식별자. 결과 귀속용이며 탐지 로직은 사용하지 않는다
 * @param versionId 버전 식별자. 결과 귀속용
 * @param text      검사 대상 텍스트. FULL이면 전체 본문, DELTA면 추가된 줄만
 * @param mimeType  원본 문서 형식. 알 수 없으면 null
 * @param scope     검사 범위
 */
public record ScanRequest(
        String fileId,
        String versionId,
        String text,
        String mimeType,
        ScanScope scope
) {
    public ScanRequest {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(versionId, "versionId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(scope, "scope");
    }

    /** 전체 검사 요청을 만든다. (RD-SRS-5.1) */
    public static ScanRequest full(String fileId, String versionId, String text, String mimeType) {
        return new ScanRequest(fileId, versionId, text, mimeType, ScanScope.FULL);
    }

    /**
     * 변경분 검사 요청을 만든다. (RD-SRS-5.2)
     *
     * <p>{@code addedLines}에는 버전 비교 결과에서 추가된 줄만 넣는다.
     * 삭제된 줄은 현재 구현에서 다루지 않으므로, 한 줄 안의 일부만 수정된 경우
     * 그 줄 전체가 새로 유입된 것으로 집계된다. 과탐 방향이므로 유출 차단 관점에서는
     * 안전한 쪽이다.
     */
    public static ScanRequest delta(String fileId, String versionId, String addedLines, String mimeType) {
        return new ScanRequest(fileId, versionId, addedLines, mimeType, ScanScope.DELTA);
    }
}
