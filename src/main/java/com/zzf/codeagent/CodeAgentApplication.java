package com.zzf.codeagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class CodeAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeAgentApplication.class, args);
    }
}
