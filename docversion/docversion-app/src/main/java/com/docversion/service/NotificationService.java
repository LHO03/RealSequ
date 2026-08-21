package com.docversion.service;

import com.docversion.mapper.AccountMapper;
import com.docversion.mapper.DocumentMapper;
import com.docversion.mapper.NotificationMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 알림 (RD-SRS-9.9).
 *
 * <p>핵심은 {@link #notifyStakeholders}이다. 이 메서드는 호출한 업무 메서드의
 * 트랜잭션 안에서 실행되어, 인앱 알림과 아웃박스 항목을 업무 변경과 <b>같은 트랜잭션</b>으로
 * 적재한다. 따라서 "변경은 기록됐는데 보낼 항목은 누락" 같은 어긋남이 생기지 않는다.
 * 실제 외부 발송은 아웃박스 워커가 별도로 처리하며, 실패 시 재시도한다.
 *
 * <p>채널: WEB(인앱)은 항상, EMAIL은 수신자 계정에 이메일이 있고 emailEnabled일 때 함께 적재.
 */
@Service
public class NotificationService {

    private final NotificationMapper mapper;
    private final UuidGenerator uuid;
    private final AccountMapper accounts;
    private final DocumentMapper documents; // 07/19 - P1-①: API 구독 인가(소유자 검사)용
    private final boolean emailEnabled;

    public NotificationService(NotificationMapper mapper, UuidGenerator uuid, AccountMapper accounts,
                               DocumentMapper documents,
                               @Value("${docversion.notify.email-enabled:true}") boolean emailEnabled) {
        this.mapper = mapper;
        this.uuid = uuid;
        this.accounts = accounts;
        this.documents = documents;
        this.emailEnabled = emailEnabled;
    }

    /**
     * 파일의 구독자(이해관계자)에게 알림. 호출자의 트랜잭션 안에서 실행되어야 한다.
     * actorId(행위 당사자)는 자기 자신에게 알림이 가지 않도록 제외한다.
     *
     * @param eventKey 이 알림을 낳은 <b>사건</b>의 식별자. 중복 방지 키의 근간이므로
     *                 서로 다른 사건이면 반드시 달라야 한다. {@link #enqueueFor} 설명 참고.
     */
    public void notifyStakeholders(String fileId, String eventKey, String subject, String message, String actorId) {
        long now = Instant.now().getEpochSecond();
        List<String> subscribers = mapper.listSubscribers(fileId);
        for (String u : subscribers) {
            if (u == null || u.equals(actorId)) {
                continue;
            }
            enqueueFor(u, fileId, eventKey, subject, message, now);
        }
    }

    /**
     * 특정 사용자 1명에게 알림 (4-B 순차 결재의 "당신 차례" 등 대상 지정 알림).
     * 브로드캐스트(notifyStakeholders)와 동일한 중복 방지·동일 트랜잭션·이메일 적재를 공유한다.
     *
     * @param eventKey 사건 식별자. 같은 사건에서 브로드캐스트와 대상 지정 알림이 함께 나갈 때는
     *                 종류를 덧붙여 구분한다(예: {@code ...:req} vs {@code ...:turn}).
     *                 그러지 않으면 두 알림이 같은 키가 되어 뒤엣것이 삼켜진다.
     */
    public void notifyUser(String userId, String fileId, String eventKey, String subject, String message) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        enqueueFor(userId.trim(), fileId, eventKey, subject, message, Instant.now().getEpochSecond());
    }

    /**
     * 알림 1건 적재 공통부: 인앱 + 아웃박스(WEB, 이메일 있으면 EMAIL). 호출자 트랜잭션에 합류.
     *
     * <p><b>P1d — 중복 방지 키를 시간이 아니라 사건으로.</b>
     * 과거에는 {@code 제목 + 파일 + 수신자 + (현재시각/300초)}를 키로 썼다. 5분이라는 시간 구간으로
     * 같은 사건을 판정한 것인데, 이는 <i>같은 제목의 서로 다른 사건</i>을 동일 사건으로 오인한다.
     * 예를 들어 5분 안에 문서를 두 번 수정하면 두 번째 "새 버전" 알림이 조용히 사라졌다.
     * 반대로 5분 경계를 넘기면 진짜 중복도 통과했다 — 어느 쪽으로도 부정확했다.
     *
     * <p>이제는 호출부가 사건 식별자를 명시적으로 넘긴다. 최종 키는 {@code eventKey + ":" + 수신자}로,
     * 수신자마다 한 행이 필요하므로 수신자를 포함한다. 사건 식별자는 이미 적재되는 이력 행
     * (승인은 {@code approval_activity}, 상태는 {@code document_status_history})의 일련번호를 쓴다.
     * 이력 행은 사건 하나당 하나씩 생기므로 사건과 정확히 일대일이며, 판정 번복 후 재판정처럼
     * "같은 사람이 같은 행위를 반복"하는 경우에도 서로 다른 키가 나온다.
     *
     * <p>시각(초)을 식별자에 섞지 않은 이유: 같은 초 안에 두 사건이 일어날 수 있고, 그러면
     * 뒤엣것이 유실된다. 버전 목록 정렬에서 이미 같은 함정을 겪었으므로 같은 실수를 반복하지 않는다.
     */
    private void enqueueFor(String u, String fileId, String eventKey, String subject, String message, long now) {
        String notifId = uuid.newId();
        String dedupKey = eventKey + ":" + u;
        int inserted = mapper.insertNotificationIgnore(
                notifId, u, now, "document", fileId, subject, message, dedupKey);
        if (inserted == 1) {
            // 같은 트랜잭션에서 아웃박스에도 적재 (발송 신뢰성)
            mapper.insertOutbox(notifId, u, "WEB", message, now, now);
            // RD-SRS-9.9 외부 채널: 수신자에게 이메일이 있으면 EMAIL 발송도 적재.
            // 같은 트랜잭션이므로 "업무는 됐는데 이메일 발송건이 안 만들어짐"은 없다.
            // (emailEnabled=false면 인앱만 — 메일 서버 없는 환경 배려)
            if (emailEnabled) {
                String email = accounts.findEmail(u);
                if (email != null && !email.isBlank()) {
                    mapper.insertOutbox(notifId, u, "EMAIL", "[" + subject + "] " + message, now, now);
                }
            }
        }
    }

    /**
     * 07/19 - P1-①: API 경유 구독 등록 — 소유자만 허용.
     * 배경(외부 리뷰 지적): 구독에 권한 검사가 없어, 임의 fileId로 자기 구독 후
     * 버전 콘텐츠(9.5, "소유자 또는 구독자" 허용)를 내려받는 인가 우회가 가능했다.
     * 승인 요청자·승인자의 자동 구독은 서비스 내부에서 subscribe()를 직접 호출하므로
     * 이 검사의 영향을 받지 않는다.
     */
    public void subscribeChecked(String fileId, String userId) {
        String owner = documents.findOwner(fileId);
        if (owner == null) {
            throw new ResourceNotFoundException("문서를 찾을 수 없습니다: " + fileId);
        }
        if (!owner.equals(userId)) {
            throw new ForbiddenOperationException(
                    "문서 소유자만 구독을 등록할 수 있습니다. (승인 관계자는 승인 요청 시 자동 등록됩니다)");
        }
        subscribe(fileId, userId);
    }

    /** 파일 구독(이해관계자 등록). 이미 있으면 무시. — 내부 자동 구독 전용 경로 */
    public void subscribe(String fileId, String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        mapper.subscribeIgnore(fileId, userId.trim(), Instant.now().getEpochSecond());
    }

    public void unsubscribe(String fileId, String userId) {
        mapper.unsubscribe(fileId, userId);
    }

    public List<String> subscribers(String fileId) {
        return mapper.listSubscribers(fileId);
    }

    // ---------- 조회/읽음 ----------
    public List<Map<String, Object>> listForUser(String user, boolean unreadOnly, int limit) {
        return mapper.listByUser(user, unreadOnly, limit);
    }

    public int unreadCount(String user) {
        return mapper.countUnread(user);
    }

    public boolean markRead(String notificationId, String user) {
        return mapper.markRead(notificationId, user, Instant.now().getEpochSecond()) > 0;
    }

    public List<Map<String, Object>> outbox(int limit) {
        return mapper.listOutbox(limit);
    }
}
