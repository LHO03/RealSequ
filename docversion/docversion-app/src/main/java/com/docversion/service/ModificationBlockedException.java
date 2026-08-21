package com.docversion.service;

/**
 * 정책 A(클러스터 1): 열린 승인 요청이 있는 동안 새 버전 업로드를 거부할 때 던진다.
 *
 * <p>"검토 중인 버전이 승인자 몰래 바뀌는" 상황 자체를 원천 차단하기 위한 것으로,
 * 자원 없음(404)도 권한 없음(403)도 아닌 <b>상태 충돌</b>이다 → 컨트롤러에서 HTTP 409로 변환.
 * 소유자는 먼저 승인 요청을 취소하거나 결재를 완료한 뒤 수정해야 한다.
 *
 * <p>onDocumentModified는 이 예외를 (파일 보상 삭제 후) 래핑하지 않고 그대로 전파해,
 * 정확한 사유·상태코드가 클라이언트에 전달되게 한다.
 *
 * <p>B 이음새: 훗날 "검토 중에도 업로드(supersede)"를 허용하려면, 이 예외를 던지는 대신
 * 기존 요청을 STALE로 종료하고 REVISION_DRAFT로 전환하는 분기를 한 곳에서만 추가하면 된다.
 */
public class ModificationBlockedException extends RuntimeException {
    public ModificationBlockedException(String message) {
        super(message);
    }
}
