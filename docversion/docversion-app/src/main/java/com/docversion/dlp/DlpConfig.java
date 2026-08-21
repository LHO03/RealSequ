package com.docversion.dlp;

import com.docversion.dlp.api.SensitiveDataScanner;
import com.docversion.dlp.rule.RuleProvider;
import com.docversion.dlp.scan.RuleBasedScanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * DLP 탐지기 배선. (RD-SRS-5.1, 5.2, 5.4)
 *
 * <p>현재는 규칙 기반 탐지기 하나만 등록한다. 향후 6.x 모델 탐지기가
 * 추가되면 여기에 분배 구현체를 두고 최우선 빈으로 지정한다.
 * 9.x 알림 발송 계층이 조건에 따라 구현체를 분배하는 것과 같은 구조다.
 *
 * <p>{@code NoopScanner}는 의도적으로 등록하지 않는다. 실수로 배선되면
 * 모든 문서가 판정 불가가 되어 검사가 조용히 멈춘다. 배선 검증이 필요할 때만
 * 임시로 바꿔 쓰는 용도이므로 기본 구성에는 두지 않는다.
 */
@Configuration
public class DlpConfig {

    @Bean
    @Primary
    public SensitiveDataScanner sensitiveDataScanner(RuleProvider ruleProvider) {
        return new RuleBasedScanner(ruleProvider);
    }
}
