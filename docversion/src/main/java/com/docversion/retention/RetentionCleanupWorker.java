package com.docversion.retention;

import com.docversion.service.RetentionPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보존 정책 자동 정리 워커 (RD-SRS-9.10).
 *
 * <p>주기적으로 활성·auto_cleanup 정책을 적용해 한도를 넘은 버전을 정리한다.
 * 활성 정책이 없으면 아무 것도 하지 않는다(기본 상태에서는 안전). 알림 아웃박스 워커와
 * 동일한 @Scheduled 메커니즘을 재사용한다.
 */
@Component
public class RetentionCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupWorker.class);

    private final RetentionPolicyService service;

    public RetentionCleanupWorker(RetentionPolicyService service) {
        this.service = service;
    }

    // 60초마다 (정리는 빈번할 필요가 없음)
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void cleanup() {
        try {
            int deleted = service.runScheduledCleanup();
            if (deleted > 0) {
                log.info("[보존] 자동 정리 — 버전 {}개 삭제", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("[보존] 자동 정리 중 오류: {}", e.getMessage());
        }
    }
}
