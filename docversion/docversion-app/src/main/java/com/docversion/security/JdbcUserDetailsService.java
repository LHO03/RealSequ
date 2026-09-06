package com.docversion.security;

import com.docversion.mapper.AccountMapper;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 계정 저장소(users/user_roles)에서 사용자를 읽어 Spring Security가 쓰는 형태로 변환한다.
 * 역할이 없으면 기본 USER로 간주한다.
 */
@Service
public class JdbcUserDetailsService implements UserDetailsService {

    private final AccountMapper accounts;

    public JdbcUserDetailsService(AccountMapper accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Map<String, Object> u = accounts.findByUsername(username);
        if (u == null) {
            throw new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + username);
        }
        boolean enabled = ((Number) u.get("enabled")).intValue() == 1;

        List<String> roles = accounts.findRoles(username);
        List<GrantedAuthority> authorities = new ArrayList<>();
        if (roles == null || roles.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        } else {
            for (String r : roles) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + r));
            }
        }

        return User.withUsername(String.valueOf(u.get("userId")))
                .password(String.valueOf(u.get("passwordHash")))
                .disabled(!enabled)
                .authorities(authorities)
                .build();
    }
}
