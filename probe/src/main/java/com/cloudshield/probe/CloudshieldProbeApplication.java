package com.cloudshield.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class CloudshieldProbeApplication {

	public static void main(String[] args) {
		SpringApplication.run(CloudshieldProbeApplication.class, args);
	}

}
