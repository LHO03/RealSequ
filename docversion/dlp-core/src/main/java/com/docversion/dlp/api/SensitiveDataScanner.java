package com.docversion.dlp.api;

/**
 * 민감 데이터 탐지기. docversion-app과 dlp-core를 잇는 유일한 이음새다. (RD-SRS-5.x)
 *
 * <p>이 인터페이스가 모듈 경계의 계약이며, 구현체는 다음과 같이 나뉜다.
 *
 * <table border="1">
 *   <caption>구현체 구성</caption>
 *   <tr><th>구현체</th><th>위치</th><th>역할</th></tr>
 *   <tr><td>RuleBasedScanner</td><td>dlp-core</td>
 *       <td>정규식 규칙 기반 탐지. 5.4를 담당하며 5.1·5.2는 입력 범위만 달리해 재사용</td></tr>
 *   <tr><td>NoopScanner</td><td>dlp-core</td>
 *       <td>항상 NOT_SENSITIVE 반환. 배선과 작업자를 먼저 검증하는 용도</td></tr>
 *   <tr><td>HeuristicScanner</td><td>dlp-core</td>
 *       <td>5.5 자리. 이번 범위에서는 골격만 두고 판정에 참여하지 않는다</td></tr>
 *   <tr><td>RemoteMlScanner</td><td>docversion-app</td>
 *       <td>향후 6.x 모델 서비스 호출. 현 단계에서는 작성하지 않는다</td></tr>
 *   <tr><td>CompositeScanner</td><td>docversion-app</td>
 *       <td>위 구현체들을 호출하고 결과를 병합. Spring 최우선 빈으로 등록</td></tr>
 * </table>
 *
 * <p>구현체는 다음을 지켜야 한다.
 *
 * <ul>
 *   <li>예외를 밖으로 던지지 않는다. 검사에 실패하면
 *       {@link ScanResult#undetermined(String, String)}을 반환한다.
 *       탐지 실패가 버전 생성이나 다른 검사기를 중단시켜서는 안 된다.</li>
 *   <li>탐지된 원문을 반환값에 담지 않는다. {@link Finding}은 마스킹된 값만 보유한다.</li>
 *   <li>상태를 갖지 않거나, 갖는다면 다중 스레드에서 안전해야 한다.
 *       배경 작업자가 동시에 호출한다.</li>
 * </ul>
 */
public interface SensitiveDataScanner {

    /**
     * 요청된 텍스트를 검사한다.
     *
     * @param request 검사 요청. 텍스트는 이미 추출된 상태여야 한다
     * @return 검사 결과. 실패 시에도 null이 아닌 UNDETERMINED 결과를 반환한다
     */
    ScanResult scan(ScanRequest request);

    /**
     * 이 탐지기의 식별자. 결과의 {@code method} 값과 감사 추적에 쓰인다.
     */
    String name();
}
