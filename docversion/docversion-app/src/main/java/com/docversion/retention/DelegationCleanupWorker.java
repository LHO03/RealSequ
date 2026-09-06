package com.docversion.retention;

import com.docversion.mapper.DelegationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 만료 위임 정리 (RD-SRS-9.7 확장, 4-C) — 목표 구상도의 DelegationCleanup 실선화.
 *
 * <p>기간이 지난 활성 위임을 주기적으로 비활성 처리한다. 판정 경로는 어차피
 * 기간(starts_at ≤ now ≤ ends_at)을 검사하므로 만료 위임으로 대리 판정이 되는 일은 없지만,
 * 표시(활성 위임 목록)와 데이터 위생을 위해 명시적으로 닫아 준다.
 * RetentionCleanupWorker와 동일한 @Scheduled 메커니즘.
 */
@Component
public class DelegationCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(DelegationCleanupWorker.class);

    private final DelegationMapper delegations;

    public DelegationCleanupWorker(DelegationMapper delegations) {
        this.delegations = delegations;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 45_000)
    public void cleanupExpired() {
        int n = delegations.deactivateExpired(Instant.now().getEpochSecond());
        if (n > 0) {
            log.info("[위임 정리] 만료 위임 {}건 비활성 처리", n);
        }
    }
}
