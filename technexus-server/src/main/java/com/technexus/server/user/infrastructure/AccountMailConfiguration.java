package com.technexus.server.user.infrastructure;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class AccountMailConfiguration {
	@Bean
	ThreadPoolTaskExecutor accountMailExecutor() {
		var executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(2);
		executor.setQueueCapacity(64);
		executor.setThreadNamePrefix("account-mail-");
		return executor;
	}
}
