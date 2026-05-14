package com.example.docver.service;

import com.example.docver.model.NotificationInfo;
import com.example.docver.repository.ActivityRepository;
import com.example.docver.repository.FileVersionRepository;
import com.example.docver.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 알림 발송 + 조회 통합 서비스.
 *
 * 의사코드의 notifyStakeholders, getDefaultStakeholders,
 * getUserNotifications, markNotificationRead, getUnreadCount 매핑.
 *
 * 시연 단순화:
 *   - Outbox 패턴 제외 (notifications 테이블에만 INSERT, 실제 발송 없음)
 *   - dedup_key 제거 (단일 사용자 시연이라 중복 없음)
 *   - 구독자 자동 결정 제외 (시연은 명시 targets만 사용 또는 보수적 합집합)
 *
 * Java 전환 시 차후 단계:
 *   - 이벤트 발행 분리 (@EventListener 패턴)
 *   - Outbox 패턴 도입 (Spring Retry + 백그라운드 잡)
 *   - 채널별 발송 어댑터 (PUSH/EMAIL/WEB)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepo;
    private final FileVersionRepository fileVersionRepo;
    private final ActivityRepository activityRepo;

    /**
     * 의사코드 notifyStakeholders 이식 (단순화).
     *
     * 시연 동작:
     *   - targets가 비어있으면 getDefaultStakeholders로 자동 산출
     *   - 각 target에게 notifications INSERT
     *   - 실제 PUSH/EMAIL 발송은 안 함 (notifications 조회로 확인)
     *
     * @param fileId 대상 파일
     * @param eventType 이벤트 타입 (version_created, approval_requested 등)
     * @param message 알림 본문
     * @param targets 명시 대상 (빈 리스트면 자동 결정)
     */
    public void notifyStakeholders(String fileId, String eventType, String message,
                                    List<String> targets) {
        // 1. 대상 결정
        Set<String> effectiveTargets;
        if (targets == null || targets.isEmpty()) {
            effectiveTargets = getDefaultStakeholders(fileId, eventType);
        } else {
            effectiveTargets = new HashSet<>(targets);
        }

        long timestamp = System.currentTimeMillis() / 1000;

        // 2. 사용자별 알림 INSERT
        for (String userId : effectiveTargets) {
            String notificationId = "notif_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            notificationRepo.insert(notificationId, userId, timestamp,
                                     fileId, eventType, message);
            log.debug("Notification sent: user={}, event={}, fileId={}", userId, eventType, fileId);
        }

        // 3. 활동 로그
        activityRepo.log("system", fileId, "notifications_sent",
                "Event: " + eventType + ", Recipients: " + effectiveTargets.size());
    }

    /**
     * 의사코드 getDefaultStakeholders 이식 (시연 단순화).
     *
     * 시연 시 사용되는 이벤트 매핑 (보고서 ③ 매트릭스 일부):
     *   version_created      → 소유자
     *   version_updated      → 소유자 + 마지막 수정자
     *   approval_requested   → 외부에서 승인자 명시 (자동 결정 안 함)
     *   approval_completed   → 요청자 + 소유자 (broadcast에서 결정자 제외는 호출자 책임)
     *   기타                  → 소유자 (보수적 기본값)
     *
     * 의사코드의 OPEN/_Any 분기는 시연 단순화에서 제외.
     */
    private Set<String> getDefaultStakeholders(String fileId, String eventType) {
        Set<String> result = new HashSet<>();
        if (fileId == null || fileId.isEmpty()) {
            return result;
        }

        switch (eventType) {
            case "version_created":
                fileVersionRepo.findOwnerByFileId(fileId).ifPresent(result::add);
                break;
            case "version_updated":
                fileVersionRepo.findOwnerByFileId(fileId).ifPresent(result::add);
                fileVersionRepo.findLastEditorByFileId(fileId).ifPresent(result::add);
                break;
            case "approval_completed":
            case "approval_rejected":
                // 시연: 요청자는 호출자가 명시 targets로 별도 처리하므로 여기는 소유자만
                fileVersionRepo.findOwnerByFileId(fileId).ifPresent(result::add);
                break;
            default:
                // 보수적 기본값: 소유자
                fileVersionRepo.findOwnerByFileId(fileId).ifPresent(result::add);
        }
        return result;
    }

    // ============================================================
    // 알림 조회 API (의사코드 ⑤⑥ 단계)
    // ============================================================

    public List<NotificationInfo> getUserNotifications(String userId, boolean unreadOnly,
                                                       int limit, int offset) {
        return notificationRepo.findByUserId(userId, unreadOnly, limit, offset);
    }

    public long getUnreadCount(String userId) {
        return notificationRepo.countUnread(userId);
    }

    public boolean markNotificationRead(String userId, String notificationId) {
        long now = System.currentTimeMillis() / 1000;
        return notificationRepo.markRead(userId, notificationId, now);
    }
}