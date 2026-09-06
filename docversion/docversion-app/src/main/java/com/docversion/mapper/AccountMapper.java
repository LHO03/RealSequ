package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
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

    /**
     * 주어진 목록 중 <b>실제로 존재하고 활성 상태인</b> 계정만 골라 돌려준다. (RD-SRS-9.7)
     *
     * <p>승인 요청을 만들 때 승인자가 실존하는지 확인하는 데 쓴다. 한 명씩 조회하지 않고
     * 한 번에 받는 이유는 왕복을 줄이려는 것도 있지만 <b>없는 사람을 한꺼번에 알려주기
     * 위해서</b>다. 첫 오타에서 멈추면 요청자는 고치고 다시 시도하기를 반복하게 된다.
     *
     * @param userIds 확인할 로그인 ID. 비어 있으면 호출하지 말 것(SQL이 성립하지 않는다)
     * @return 존재하며 enabled=1인 ID만. 입력에 없던 값은 포함되지 않는다
     */
    List<String> findExistingEnabled(@Param("userIds") Collection<String> userIds);

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
