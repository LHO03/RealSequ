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
                   @Param("createdAt") long createdAt);

    int insertRole(@Param("userId") String userId,
                   @Param("role") String role,
                   @Param("grantedBy") String grantedBy,
                   @Param("grantedAt") long grantedAt);
}
