package com.sashkomusic.libraryagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SmLibraryAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmLibraryAgentApplication.class, args);
	}

}
