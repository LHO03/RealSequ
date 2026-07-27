package com.docversion.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배경 워커 스케줄링 활성화 스위치.
 *
 * <p>기존에는 {@code @EnableScheduling}이 애플리케이션 클래스에 직접 붙어 있어 항상 켜졌다.
 * 그 결과 테스트에서 다음 문제가 있었다.
 *
 * <p><b>문제</b>: Spring 테스트 프레임워크는 클래스별 컨텍스트를 <b>캐시에 살려둔다</b>.
 * 반면 Testcontainers는 클래스가 끝나면 그 클래스의 DB 컨테이너를 내린다.
 * 따라서 이미 끝난 클래스의 스케줄러가 <b>사라진 DB</b>를 계속 두드리고,
 * 커넥션 타임아웃(30초)을 기다리다 JVM 종료까지 지연시켰다
 * ({@code Surefire is going to kill self fork JVM}). 매 실행마다 30초가 낭비됐고,
 * 무엇보다 실패가 아닌 예외 스택이 로그를 뒤덮어 진짜 문제를 찾기 어렵게 만들었다.
 *
 * <p><b>기존 대응의 한계</b>: 워커마다 시작 지연을 크게 잡는 방식은 테스트 클래스마다
 * 속성을 넣어야 해서 잊기 쉬웠다(실제로 5개 중 2개에서 누락됐다).
 *
 * <p><b>해결</b>: 스케줄링 자체를 이 한 곳에서 끈다. 테스트 클래스패스의
 * {@code application.properties}가 {@code docversion.scheduling.enabled=false}를 지정하므로
 * 모든 테스트에 자동 적용되며, 새 테스트를 추가해도 따로 챙길 것이 없다.
 *
 * <p>테스트가 워커 동작을 검증할 때는 스케줄러에 기대지 않고
 * {@code DiffJobWorker.runOnce()}처럼 직접 호출한다 — 결정적이라 오히려 낫다.
 *
 * <p>속성이 없으면 켜진다({@code matchIfMissing = true}). 운영 기동에는 영향이 없다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(
        name = "docversion.scheduling.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SchedulingConfig {
}
