package com.nexusforge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // 启用 @Scheduled 定时任务(账号生命周期 expire-deletions 等)
public class NexusForgeApplication {

	public static void main(String[] args) {
		SpringApplication.run(NexusForgeApplication.class, args);
	}

}
