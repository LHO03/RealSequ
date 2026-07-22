package com.docversion.config;

import com.docversion.mapper.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

/**
 * 데모용 기본 계정 생성 (인증 1단계).
 * users 테이블이 비어 있을 때만 alice/bob(USER)과 admin(ADMIN)을 생성한다.
 * BCrypt 해시 생성은 SQL에서 불가하므로 애플리케이션 기동 시 시드한다.
 *
 * 기본 자격(데모 전용): alice/alice123, bob/bob123, admin/admin123
 *
 * <p>07/19 - P1-⑤(외부 리뷰 지적): {@code @Profile("demo")}로 격리한다.
 * 운영 환경에서 빈 DB를 연결해도 알려진 관리자 비밀번호(admin/admin123)가
 * 자동 생성되지 않도록, 이 시더는 demo 프로필에서만 활성화된다.
 * 기본 프로필이 demo(application.yml)이므로 개발·시연 흐름은 그대로 유지되고,
 * 운영에서는 SPRING_PROFILES_ACTIVE=prod 로 기동해 이 빈을 제외한 뒤
 * AD/LDAP 또는 일회성 관리자 초기화 절차로 계정을 만든다.
 */
@Configuration
@Profile("demo")
public class DefaultAccountsInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultAccountsInitializer.class);

    @Bean
    public ApplicationRunner seedAccounts(AccountMapper accounts, PasswordEncoder encoder) {
        return args -> {
            if (accounts.countUsers() > 0) {
                return;
            }
            long now = Instant.now().getEpochSecond();
            create(accounts, encoder, now, "alice", "alice123", "Alice", "USER");
            create(accounts, encoder, now, "bob", "bob123", "Bob", "USER");
            create(accounts, encoder, now, "admin", "admin123", "Admin", "ADMIN");
            log.info("[인증] 기본 데모 계정 생성: alice/bob(USER), admin(ADMIN)");
        };
    }

    private void create(AccountMapper accounts, PasswordEncoder encoder, long now,
                        String id, String rawPw, String name, String role) {
        // 데모 이메일: {id}@docversion.local — MailHog 시연에서 실제 수신 확인용 (RD-SRS-9.9)
        accounts.insertUser(id, encoder.encode(rawPw), name, id + "@docversion.local", now);
        accounts.insertRole(id, role, "system", now);
    }
}
