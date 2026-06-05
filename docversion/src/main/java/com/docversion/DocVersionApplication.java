package com.docversion;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DocumentVersionWorkflow 알파 - 버전 생명주기 슬라이스.
 * C++ 프로토타입(DocumentVersionWorkflowAPI.cpp / Diffservice.h / Schema.sql)을
 * Spring Boot 3 + MyBatis(코어) + MariaDB로 전환한 첫 수직 슬라이스.
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.docversion.mapper")
public class DocVersionApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocVersionApplication.class, args);
    }
}
