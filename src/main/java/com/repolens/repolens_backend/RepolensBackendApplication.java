package com.repolens.repolens_backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableCaching        // Enable @Cacheable, @CacheEvict
@EnableRetry          // Enable @Retryable, @Recover
public class RepolensBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(RepolensBackendApplication.class, args);
	}

}
