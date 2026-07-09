package com.docversion.notification;

import com.docversion.mapper.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 채널 라우팅 발송기 (RD-SRS-9.9 실발송).
 *
 * <p>아웃박스 워커가 꺼낸 항목을 채널별로 실제 발송한다.
 * <ul>
 *   <li><b>WEB</b>: 인앱 알림은 적재 시점에 이미 notifications 테이블에 저장되어 화면에
 *       보이므로, 여기서는 성공으로 확정만 한다(별도 발송 없음).</li>
 *   <li><b>EMAIL</b>: 수신자 계정의 이메일 주소로 SMTP 발송. 개발/시연 환경에서는
 *       MailHog(가짜 SMTP, localhost:8025 웹함)로 수신을 눈으로 확인한다.</li>
 *   <li>알 수 없는 채널: 실패 반환 → 아웃박스가 재시도 후 DLQ로 격리(조용히 삼키지 않음).</li>
 * </ul>
 *
 * <p>발송 실패는 예외/false로 워커에 알려지고, 워커가 지수 백오프 재시도 → 3회 초과 시
 * DLQ 처리한다. 이 클래스는 "한 번 보내기"만 책임진다.
 *
 * <p>{@code @Primary}: 기존 MockNotificationSender를 대체해 주입된다.
 */
@Component
@Primary
public class RoutingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(RoutingNotificationSender.class);

    private final JavaMailSender mail;
    private final AccountMapper accounts;

    public RoutingNotificationSender(JavaMailSender mail, AccountMapper accounts) {
        this.mail = mail;
        this.accounts = accounts;
    }

    @Override
    public boolean send(String channel, String userId, String payload) {
        switch (channel) {
            case "WEB":
                // 인앱은 적재가 곧 전달(화면 조회). 발송 확정만.
                return true;
            case "EMAIL":
                return sendEmail(userId, payload);
            default:
                log.warn("[알림 발송] 알 수 없는 채널: {} (user={})", channel, userId);
                return false;
        }
    }

    private boolean sendEmail(String userId, String payload) {
        String to = accounts.findEmail(userId);
        if (to == null || to.isBlank()) {
            // 적재 후 이메일이 제거된 드문 경우 — 보낼 곳이 없으니 성공 처리(무한 재시도 방지)
            log.info("[알림 발송] user={} 이메일 없음 — EMAIL 건 스킵", userId);
            return true;
        }
        // payload 형식: "[제목] 본문" (NotificationService 적재 시 조립)
        String subject = "[DocVersion] 알림";
        String body = payload;
        if (payload != null && payload.startsWith("[")) {
            int end = payload.indexOf(']');
            if (end > 1) {
                subject = "[DocVersion] " + payload.substring(1, end);
                body = payload.substring(end + 1).trim();
            }
        }
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom("noreply@docversion.local");
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(body);
        mail.send(msg); // 실패 시 예외 → 워커가 재시도/DLQ
        log.info("[알림 발송] EMAIL to={} subject={}", to, subject);
        return true;
    }
}
