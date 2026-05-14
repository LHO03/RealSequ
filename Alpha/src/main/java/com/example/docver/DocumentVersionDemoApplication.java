package com.example.docver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * DocumentVersionWorkflowAPI 알파 시연 애플리케이션.
 *
 * 의사코드(DocumentVersionWorkflowAPI.cpp, 3,497줄)의 시나리오 1 경로만
 * Java Spring으로 이식. 박사님 알파 시연 데모 목적.
 *
 * 데모 시나리오 1 (보고서 7.2.1):
 *   사용자 A 파일 업로드 → 사용자 A 파일 수정 → 사용자 A 승인 요청 →
 *   사용자 B(승인자) 알림 수신 → 사용자 B 승인 → 사용자 A 최종 알림 확인
 *
 * 의도적으로 제외 (Java 전환 차후 단계):
 *   - 합의 모델 다중 지원 (THRESHOLD + required=1 단순화)
 *   - 위임, 보존 정책, 매트릭스 우회 메서드
 *   - Outbox 패턴 (알림 INSERT만, 실제 발송 없음)
 *   - DiffService 본체 (단순 "Modified" 메시지로 대체)
 *   - 6개 포맷 텍스트 추출
 *   - 백그라운드 잡 (@Scheduled)
 */
@SpringBootApplication
public class DocumentVersionDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentVersionDemoApplication.class, args);
    }
}
