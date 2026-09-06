package com.docversion.service;

import com.docversion.mapper.AccountMapper;
import com.docversion.mapper.DocumentMapper;
import com.docversion.mapper.NotificationMapper;
import org.springframework.stereotype.Service;

/**
 * 07/19 - P1-②: 문서 객체 인가의 단일 관문 (외부 리뷰의 DocumentAccessPolicy 제안 수용).
 *
 * <p>배경: 읽기 API(버전 목록·diff·상태·승인·구독자·이력)가 전면 개방되어 있었고,
 * 각 서비스가 개별적으로 소유자 검사를 흩어 놓아 검사 누락(예: 구독 경유 열람 우회)이
 * 생기기 쉬웠다. "누가 이 문서를 볼 수 있는가"의 판단을 이 클래스 한 곳으로 모은다.
 *
 * <p><b>읽기 자격(requireRead)</b> — 개인 소유 모델 기준:
 *   ① 소유자  ② 구독자(이해관계자 — 승인 요청자·승인자는 요청 시 자동 등록)
 *   ③ ADMIN(운영 점검). 그 외는 403, 문서 없음은 404 사유로 던진다.
 *
 * <p>쓰기·승인 자격은 기존대로 각 서비스의 규칙(소유자 검사, 승인자 명단, 워크플로 경로)이
 * 담당한다 — 이 클래스는 우선 "읽기"의 단일 관문으로 도입하고, 추후 ACL(VIEWER/EDITOR)이
 * 생기면 requireWrite/requireApprove를 같은 자리에서 확장한다.
 */
@Service
public class DocumentAccessPolicy {

    private final DocumentMapper documents;
    private final NotificationMapper subscriptions;
    private final AccountMapper accounts;

    public DocumentAccessPolicy(DocumentMapper documents, NotificationMapper subscriptions,
                                AccountMapper accounts) {
        this.documents = documents;
        this.subscriptions = subscriptions;
        this.accounts = accounts;
    }

    /**
     * 문서 읽기 자격 검사. 통과 시 소유자 ID 반환(호출부에서 재조회 방지용).
     *
     * @throws ResourceNotFoundException    문서 없음 (컨트롤러 어드바이스에서 404)
     * @throws ForbiddenOperationException 자격 없음 (컨트롤러에서 403)
     */
    public String requireRead(String fileId, String userId) {
        String owner = documents.findOwner(fileId);
        if (owner == null) {
            throw new ResourceNotFoundException("문서를 찾을 수 없습니다: " + fileId);
        }
        if (userId == null || userId.isBlank()) {
            throw new ForbiddenOperationException("로그인이 필요합니다.");
        }
        // 07/20: findRoles는 역할 행이 없으면 null을 반환할 수 있다(MyBatis). 그대로 .contains를
        //   부르면 NPE → 500이 되고, 이 요청 경로에서 결과적으로 잘못된 403 응답으로 나타난다.
        //   (JdbcUserDetailsService는 이미 null/empty 방어를 하고 있어 로그인은 정상이었다.)
        java.util.List<String> roles = accounts.findRoles(userId);
        boolean isAdmin = roles != null && roles.contains("ADMIN");
        java.util.List<String> subs = subscriptions.listSubscribers(fileId);
        boolean isSubscriber = subs != null && subs.contains(userId);
        if (owner.equals(userId)
                || isSubscriber
                || isAdmin) {
            return owner;
        }
        throw new ForbiddenOperationException("문서 소유자 또는 이해관계자만 조회할 수 있습니다.");
    }
}
