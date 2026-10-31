package com.aicoo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AicooApplication {
    public static void main(String[] args) { SpringApplication.run(AicooApplication.class, args); }
}
