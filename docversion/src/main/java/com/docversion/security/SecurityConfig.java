package com.docversion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import jakarta.servlet.DispatcherType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Map;

/**
 * 인증 1단계 — 세션 기반 로그인 골격.
 *
 * <p>인증 2단계 진행 중: 작성자가 결정되는 "쓰기" 창구는 로그인한 사용자로만 동작하도록
 * 잠근다(클라이언트가 보낸 userId를 믿지 않고 서버가 세션에서 신원을 주입). 아직 전환하지
 * 않은 창구는 기존 동작 유지를 위해 열어 둔다. 슬라이스를 전환할 때마다 잠금 범위를 넓힌다.
 *
 * <p>로그인은 세션(JSESSIONID 쿠키) 방식이다. 데모 단순화를 위해 CSRF는 비활성화했으며,
 * 이는 보안 강화 단계에서 다시 다룬다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final ObjectMapper json = new ObjectMapper();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // 07/22: 컨트롤러가 던진 404/500 오류는 Spring 내부에서 /error로 재디스패치된다.
                //   /error는 /api/** 패턴이 아니므로 anyRequest().denyAll()에 걸려, 원래 404·500이어야
                //   할 응답이 accessDeniedHandler의 403으로 위장되는 문제가 있었다. ERROR 디스패치는
                //   인가 검사에서 제외하여, 실제 상태코드(404는 404, 오류는 500)가 드러나게 한다.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                // 07/19 - P1-②: 기본 거부(deny-by-default)로 전환 (외부 리뷰 지적 수용).
                //   기존 "필요한 곳만 잠그고 나머지 permitAll" 방식은 새 엔드포인트가
                //   추가될 때마다 자동으로 공개되는 함정이 있었다(버전 목록·상태·승인
                //   이력 등 문서 메타데이터가 실제로 무인증 노출됨). 이제는 공개 목록을
                //   명시하고, 나머지 /api/**는 로그인 필수 + 각 창구의 객체 인가
                //   (DocumentAccessPolicy.requireRead 등)로 이중 검사한다.
                .requestMatchers("/", "/index.html", "/favicon.ico").permitAll()
                .requestMatchers("/api/auth/**").permitAll()          // 로그인/로그아웃/내 정보
                .requestMatchers("/actuator/health").permitAll()      // 기동 확인
                // 관리자 전용 영역
                .requestMatchers(HttpMethod.GET, "/api/notifications/outbox").hasRole("ADMIN")
                .requestMatchers("/api/retention/**").hasRole("ADMIN")
                // 나머지 API 전부 로그인 필수 (객체 단위 권한은 서비스/정책 계층에서)
                .requestMatchers("/api/**").authenticated()
                // API도 정적 자원도 아닌 나머지는 전부 거부
                .anyRequest().denyAll()
            )
            // 07/20: CSRF 비활성화.
            //   본 모듈(RD-SRS-9.x 문서 형상관리)은 클라이언트 에이전트가 호출하는 백엔드
            //   API로, 브라우저 쿠키 자동전송에 기인하는 CSRF는 이 사용 형태에 해당하지 않는다.
            //   또한 CSRF는 9.x 명세 조항에 없다. (에이전트↔서버 통신 보호는 상위 명세의
            //   "암호화 채널 + 인증" 요구에 따라 배포 단계에서 TLS/토큰으로 처리 예정.)
            //   로그인·사용자 식별·객체 인가(소유자/이해관계자 검사)는 9.x 기능 동작에
            //   필요하므로 그대로 유지한다.
            .csrf(csrf -> csrf.disable())
            // 미인증으로 보호된 창구 호출 시: 로그인 페이지 리다이렉트가 아니라 401 JSON
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    writeJson(res, 401, Map.of("ok", false, "error", "로그인이 필요합니다.")))
                // 인증 3단계(3-C): 로그인은 됐지만 권한이 없는 경우 — 403 JSON
                .accessDeniedHandler((req, res, e) ->
                    writeJson(res, 403, Map.of("ok", false, "error", "이 작업을 수행할 권한이 없습니다."))))
            // 세션 기반 폼 로그인. 로그인 처리 창구를 /api/auth/login으로.
            .formLogin(form -> form
                .loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, a) -> writeJson(res, 200,
                        Map.of("ok", true, "user", a.getName())))
                .failureHandler((req, res, e) -> writeJson(res, 401,
                        Map.of("ok", false, "error", "아이디 또는 비밀번호가 올바르지 않습니다.")))
            )
            .logout(out -> out
                .logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((req, res, a) -> writeJson(res, 200, Map.of("ok", true)))
            );
        return http.build();
    }

    private void writeJson(HttpServletResponse res, int status, Map<String, Object> body) {
        try {
            res.setStatus(status);
            res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write(json.writeValueAsString(body));
        } catch (Exception ignored) {
            // 응답 작성 실패는 무시(연결 종료 등)
        }
    }
}
