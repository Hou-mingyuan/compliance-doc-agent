package com.portfolio.compliance;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.portfolio.compliance.config.AppProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@MapperScan("com.portfolio.compliance.mapper")
public class ComplianceDocAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceDocAgentApplication.class, args);
    }
}
