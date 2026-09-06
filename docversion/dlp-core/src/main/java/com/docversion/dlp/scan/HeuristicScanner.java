package com.docversion.dlp.scan;

import com.docversion.dlp.api.ScanRequest;
import com.docversion.dlp.api.ScanResult;
import com.docversion.dlp.api.SensitiveDataScanner;

/**
 * 키워드 기반 민감도 탐지기. (RD-SRS-5.5 — 이번 구현 범위 제외)
 *
 * <p>골격만 두고 판정에는 참여하지 않는다. 이번 단계의 구현 대상은
 * 5.1, 5.2, 5.4이며 5.5는 제외하기로 정해졌다.
 *
 * <p>선행 산출물은 이 자리에 키워드 출현 횟수를 정규화한 점수를 두고
 * ml_score라 불렀으나, 실제 학습 모델은 없었다. 선행 문서 자신도
 * "명칭은 ml_score이지만 실제로는 키워드 기반(휴리스틱 흉내)"이라고
 * 기술하고 있다. 명세가 요구하는 수준을 충족하려면 6.x 모델 자산과
 * 연동해야 하므로, 이 클래스는 그때 채워진다.
 *
 * <p>규칙 저장소(dlp_keywords)는 V14에서 스키마만 만들어 두었다.
 * 채울 때 마이그레이션이 다시 필요하지 않도록 미리 자리를 잡은 것이다.
 *
 * <p>현재는 항상 판정 불가를 반환한다. 빈 결과를 NOT_SENSITIVE로 돌려주면
 * 병합 단계에서 "휴리스틱이 안전하다고 판단함"으로 오인될 수 있다.
 */
public final class HeuristicScanner implements SensitiveDataScanner {

    public static final String NAME = "HEURISTIC";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ScanResult scan(ScanRequest request) {
        return ScanResult.undetermined(NAME, "RD-SRS-5.5 미구현(이번 범위 제외)");
    }
}
