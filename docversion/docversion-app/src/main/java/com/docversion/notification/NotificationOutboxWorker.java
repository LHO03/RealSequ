package com.docversion.notification;

import com.docversion.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 아웃박스 발송 워커 (RD-SRS-9.9).
 *
 * <p>주기적으로 PENDING 항목을 claim하여 {@link NotificationSender}로 발송한다.
 * 성공 → SENT. 실패 → 재시도 횟수에 따라 백오프 후 PENDING 복귀, 3회 초과 시 DLQ.
 * claim(PENDING→PROCESSING) 방식으로 다중 워커에서도 한 항목을 한 번만 처리한다.
 *
 * <p>주의: 이 워커는 업무 트랜잭션과 분리되어 있다. 아웃박스 항목 자체는 업무 변경과
 * 같은 트랜잭션에서 적재되므로(NotificationService), "기록은 됐는데 발송 안 됨"은
 * 발생하지 않고, 발송만 이 워커가 책임지고 재시도한다.
 */
@Component
public class NotificationOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutboxWorker.class);
    private static final int BATCH = 20;
    private static final int MAX_RETRY = 3;
    /** 07/12 - I-4: 이 시간(초) 넘게 PROCESSING이면 워커 사망으로 보고 회수. 발송 1건 처리 시간 대비 충분히 크게. */
    private static final long STALE_LOCK_SECONDS = 300;

    private final NotificationMapper mapper;
    private final NotificationSender sender;
    private final String workerId = UUID.randomUUID().toString();

    public NotificationOutboxWorker(NotificationMapper mapper, NotificationSender sender) {
        this.mapper = mapper;
        this.sender = sender;
    }

    @Scheduled(fixedDelay = 5000) // 5초마다
    public void process() {
        long now = Instant.now().getEpochSecond();
        // 07/12 - I-4: 죽은 워커가 남긴 고아 PROCESSING 회수 (자기 자신이 정상 처리 중인
        //   항목은 locked_at이 최근이므로 걸리지 않는다)
        int reclaimed = mapper.reclaimStale(now - STALE_LOCK_SECONDS);
        if (reclaimed > 0) {
            log.warn("[아웃박스] 고아 PROCESSING {}건을 PENDING으로 회수 (워커 중단 흔적)", reclaimed);
        }
        List<Map<String, Object>> batch = mapper.findSendable(now, BATCH);
        for (Map<String, Object> row : batch) {
            long id = ((Number) row.get("id")).longValue();
            // claim: 다른 워커가 이미 가져갔으면 0 → 건너뜀
            if (mapper.claim(id, workerId, now) == 0) {
                continue;
            }
            String channel = String.valueOf(row.get("channel"));
            String userId = String.valueOf(row.get("userId"));
            String payload = String.valueOf(row.get("payload"));
            int retryCount = ((Number) row.get("retryCount")).intValue();
            try {
                boolean ok = sender.send(channel, userId, payload);
                if (ok) {
                    mapper.markSent(id, Instant.now().getEpochSecond());
                } else {
                    backoffOrDlq(id, retryCount, "발송 부품이 실패(false)를 반환");
                }
            } catch (Exception e) {
                backoffOrDlq(id, retryCount, e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
    }

    private void backoffOrDlq(long id, int retryCount, String error) {
        int next = retryCount + 1;
        if (next > MAX_RETRY) {
            mapper.markDlq(id, error);
            log.warn("[아웃박스] id={} DLQ 처리 (재시도 {}회 초과): {}", id, MAX_RETRY, error);
            return;
        }
        long delay = backoffSeconds(next);
        long retryAfter = Instant.now().getEpochSecond() + delay;
        mapper.markRetry(id, next, retryAfter, error);
        log.info("[아웃박스] id={} 재시도 {}회 예약(+{}s): {}", id, next, delay, error);
    }

    private long backoffSeconds(int retry) {
        // 1회: 60s, 2회: 300s, 3회: 1800s
        switch (retry) {
            case 1: return 60;
            case 2: return 300;
            default: return 1800;
        }
    }
}
