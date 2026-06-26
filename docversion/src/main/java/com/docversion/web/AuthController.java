package com.docversion.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 인증 상태 조회 창구 (1단계).
 * 로그인/로그아웃 자체는 Spring Security 필터(/api/auth/login · /logout)가 처리하고,
 * 여기서는 "현재 로그인한 사람이 누구인지"를 돌려준다.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 현재 로그인 사용자. 비로그인(익명)이면 authenticated=false. */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        boolean anonymous = (auth == null)
                || !auth.isAuthenticated()
                || "anonymousUser".equals(auth.getName());
        if (anonymous) {
            return Map.of("authenticated", false);
        }
        List<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toList();
        return Map.of("authenticated", true, "user", auth.getName(), "roles", roles);
    }
}
