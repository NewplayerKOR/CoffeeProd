package com.back.coffeeprod;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling    // 스케줄러 활성화
@EnableJpaAuditing
@SpringBootApplication
public class CoffeeprodApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoffeeprodApplication.class, args);
    }
}
