package com.restos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RestOsBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(RestOsBackendApplication.class, args);
	}

}
