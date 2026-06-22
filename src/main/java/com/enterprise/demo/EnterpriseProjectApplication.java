package com.enterprise.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // activates TokenCleanupScheduler and any future @Scheduled tasks
public class EnterpriseProjectApplication {

	public static void main(String[] args) {
		SpringApplication.run(EnterpriseProjectApplication.class, args);
	}
}
