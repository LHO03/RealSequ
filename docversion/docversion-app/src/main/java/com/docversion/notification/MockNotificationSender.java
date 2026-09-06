package com.docversion.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 모의 발송 부품 (RD-SRS-9.9). 외부 연동이 없는 현재 단계의 기본 구현.
 *
 * <p>실제로 외부로 보내지 않고, "발송했다"는 사실만 로그로 남기고 성공을 반환한다.
 * 인앱 알림은 이미 notifications 테이블에 저장되어 화면에서 보이므로, 이 부품은
 * "외부 채널 발송"의 자리만 차지한다. 추후 실제 채널 연동 시 이 클래스를 대체한다.
 */
// @Component 제거: RoutingNotificationSender(@Primary)로 대체됨 — 이메일 실발송 도입.
// 클래스는 "모의 발송" 예시 기록용으로 남겨 둔다 (필요 시 테스트에서 수동 생성).
public class MockNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(MockNotificationSender.class);

    @Override
    public boolean send(String channel, String userId, String payload) {
        // 실제 발송 대신 기록만. (외부 연동 시 이 부분을 실제 발송으로 교체)
        log.info("[알림 발송(모의)] channel={} user={} payload={}", channel, userId, payload);
        return true;
    }
}
