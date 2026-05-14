package com.example.docver.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 알림 1건. notifications 테이블 매핑.
 * getUserNotifications 반환 타입.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationInfo {
    private String notificationId;
    private String user;
    private long timestamp;
    private String objectType;
    private String objectId;       // 보통 fileId
    private String subject;        // 이벤트 타입 (version_created, approval_requested 등)
    private String message;
    private Long readAt;           // NULL = 안 읽음
}
