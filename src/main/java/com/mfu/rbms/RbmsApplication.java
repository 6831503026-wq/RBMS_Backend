package com.mfu.rbms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class RbmsApplication {

	public static void main(String[] args) {
		SpringApplication.run(RbmsApplication.class, args);
	}

}
