package com.docversion.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * activity 테이블 매퍼 (RD-SRS-9.3). logDocumentChangeHistory의 이력 INSERT.
 * `timestamp`, `user`가 예약어이므로 XML에서 백틱 처리.
 */
@Mapper
public interface ActivityMapper {

    int insertActivity(@Param("timestamp") long timestamp,
                       @Param("user") String user,
                       @Param("affecteduser") String affecteduser,
                       @Param("subject") String subject,
                       @Param("subjectparams") String subjectparams,
                       @Param("file") String file,
                       @Param("objectType") String objectType,
                       @Param("objectId") String objectId);
}
