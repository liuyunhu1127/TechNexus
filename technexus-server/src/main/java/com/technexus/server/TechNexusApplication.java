package com.technexus.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.technexus")
@EnableScheduling
public class TechNexusApplication {
	public static void main(String[] args) {
		SpringApplication.run(TechNexusApplication.class, args);
	}
}
