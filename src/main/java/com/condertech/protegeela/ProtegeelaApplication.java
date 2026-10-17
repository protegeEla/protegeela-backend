package com.condertech.protegeela;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProtegeelaApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProtegeelaApplication.class, args);
	}

}
