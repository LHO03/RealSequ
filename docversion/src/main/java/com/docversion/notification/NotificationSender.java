package com.docversion.notification;

/**
 * 실제 알림 발송 부품의 약속(인터페이스). RD-SRS-9.9.
 *
 * <p>아웃박스 워커가 PENDING 항목을 꺼내 이 부품으로 발송한다. 지금은 외부 연동이 없으므로
 * 모의 구현(MockNotificationSender)이 끼워지며, 추후 실제 이메일/푸시가 필요하면
 * 이 약속을 구현한 부품을 새로 만들어 교체하면 된다(상위 코드 무수정).
 * 사용자 ID만 받는다 — 이메일 주소 등 채널 정보는 발송 부품이 ID로 조회/해석한다.
 */
public interface NotificationSender {

    /**
     * 발송 시도.
     * @return 성공 여부. 실패(false 또는 예외)면 아웃박스가 재시도/ DLQ 처리.
     */
    boolean send(String channel, String userId, String payload);
}
