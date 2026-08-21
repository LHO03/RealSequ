package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 사용자 계정·역할 (인증 1단계) 데이터 접근.
 */
@Mapper
public interface AccountMapper {

    /** 로그인 ID로 계정 조회. user_id, password_hash, display_name, enabled. 없으면 null. */
    Map<String, Object> findByUsername(@Param("userId") String userId);

    /** 사용자의 역할 목록(USER/ADMIN). 없으면 빈 목록(애플리케이션은 기본 USER로 간주). */
    List<String> findRoles(@Param("userId") String userId);

    int countUsers();

    int insertUser(@Param("userId") String userId,
                   @Param("passwordHash") String passwordHash,
                   @Param("displayName") String displayName,
                   @Param("email") String email,
                   @Param("createdAt") long createdAt);

    /** 알림 이메일 발송용 수신 주소. 없거나 비었으면 null → 인앱만. (RD-SRS-9.9) */
    String findEmail(@Param("userId") String userId);

    int insertRole(@Param("userId") String userId,
                   @Param("role") String role,
                   @Param("grantedBy") String grantedBy,
                   @Param("grantedAt") long grantedAt);
}
