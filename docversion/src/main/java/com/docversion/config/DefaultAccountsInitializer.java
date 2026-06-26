package com.docversion.config;

import com.docversion.mapper.AccountMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

/**
 * 데모용 기본 계정 생성 (인증 1단계).
 * users 테이블이 비어 있을 때만 alice/bob(USER)과 admin(ADMIN)을 생성한다.
 * BCrypt 해시 생성은 SQL에서 불가하므로 애플리케이션 기동 시 시드한다.
 *
 * 기본 자격(데모 전용): alice/alice123, bob/bob123, admin/admin123
 */
@Configuration
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
        accounts.insertUser(id, encoder.encode(rawPw), name, now);
        accounts.insertRole(id, role, "system", now);
    }
}
