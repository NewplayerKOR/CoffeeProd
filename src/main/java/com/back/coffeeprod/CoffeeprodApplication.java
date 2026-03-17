package com.back.coffeeprod;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class CoffeeprodApplication {

	public static void main(String[] args) {
		SpringApplication.run(CoffeeprodApplication.class, args);
	}

}
