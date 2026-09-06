package com.docversion.dlp.scan;

import com.docversion.dlp.api.ScanRequest;
import com.docversion.dlp.api.ScanResult;
import com.docversion.dlp.api.SensitiveDataScanner;

/**
 * 아무것도 탐지하지 않는 검사기. 배선 검증용이다.
 *
 * <p>탐지 엔진이 준비되기 전에도 작업 적재, 워커 상태 전이, 결과 저장,
 * 조회 인터페이스를 끝까지 동작시켜 확인할 수 있게 한다.
 *
 * <p>판정을 NOT_SENSITIVE가 아니라 UNDETERMINED로 반환하는 점이 중요하다.
 * 이 검사기가 실수로 운영에 배선되면 전 문서가 "안전"으로 표시되어
 * 유출 차단이 조용히 무력화된다. 판정 불가로 두면 검사가 이루어지지
 * 않았다는 사실이 결과에 드러난다.
 */
public final class NoopScanner implements SensitiveDataScanner {

    public static final String NAME = "NOOP";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ScanResult scan(ScanRequest request) {
        return ScanResult.undetermined(NAME, "탐지기가 배선되지 않았습니다(NoopScanner)");
    }
}
