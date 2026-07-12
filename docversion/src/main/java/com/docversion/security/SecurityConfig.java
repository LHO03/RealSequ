package com.docversion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
                // 2-B 전환: 버전·업로드 "쓰기" 창구는 로그인 필수
                .requestMatchers(HttpMethod.POST, "/api/documents/upload", "/api/documents").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/documents/*/versions").authenticated()
                // 2-C 전환: 문서 상태 변경도 로그인 필수
                .requestMatchers(HttpMethod.POST, "/api/documents/*/status").authenticated()
                // 2-D 전환: 승인 워크플로(요청·승인·반려·취소)도 로그인 필수
                .requestMatchers(HttpMethod.POST, "/api/documents/*/approval/**").authenticated()
                // 2-E 전환: 내 알림·구독은 로그인 필수
                .requestMatchers(HttpMethod.GET, "/api/notifications").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/notifications/*/read").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/documents/*/subscribe", "/api/documents/*/unsubscribe").authenticated()
                // 07/12 - I-2: 읽기 개방 범위 축소.
                //   outbox는 전 사용자의 알림 payload(누가 누구에게 어떤 문서로 결재를 올렸는지)가
                //   담기므로 운영 점검용 — 관리자 전용. diff는 문서 본문 텍스트(hunks)가 그대로
                //   내려가는 내용성 정보라 최소 로그인 필수. 버전 콘텐츠(9.5 열람)도 동일.
                .requestMatchers(HttpMethod.GET, "/api/notifications/outbox").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/documents/*/diff").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/documents/*/versions/*/content").authenticated()
                // 인증 3단계(3-C): 보존 정책은 관리자 전용 (조회 포함 — 정책 관리는 운영 영역).
                // hasRole("ADMIN") = user_roles에 ADMIN이 있는 계정만. 그 외 로그인 사용자는 403.
                .requestMatchers("/api/retention/**").hasRole("ADMIN")
                // 4-C: 승인 위임 — "나의 위임"이므로 조회 포함 로그인 필수
                .requestMatchers("/api/approval/delegation/**").authenticated()
                // 그 외(읽기, 아직 미전환 창구, 정적 자원, 로그인)는 열어 둠
                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf.disable())
            // 미인증으로 보호된 창구 호출 시: 로그인 페이지 리다이렉트가 아니라 401 JSON
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    writeJson(res, 401, Map.of("ok", false, "error", "로그인이 필요합니다.")))
                // 인증 3단계(3-C): 로그인은 됐지만 권한이 없는 경우 — 403 JSON
                .accessDeniedHandler((req, res, e) ->
                    writeJson(res, 403, Map.of("ok", false, "error", "관리자(ADMIN)만 사용할 수 있는 기능입니다."))))
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
