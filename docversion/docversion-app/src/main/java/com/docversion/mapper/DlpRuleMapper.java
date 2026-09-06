package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

/**
 * DLP 탐지 규칙 조회. (RD-SRS-5.4)
 *
 * <p>규칙은 시동 시 1회 적재하여 컴파일된 형태로 메모리에 보관한다.
 * 문서마다 이 매퍼를 호출하지 않는다. 정규식 컴파일 비용이 크기 때문이며,
 * 이것이 규칙을 DB에 두면서도 탐지 경로에서는 DB를 건드리지 않는 구조의 핵심이다.
 */
@Mapper
public interface DlpRuleMapper {

    /** 활성 규칙 전체. 비활성(is_active=0)은 제외한다. */
    List<Map<String, Object>> selectActivePatterns();
}
