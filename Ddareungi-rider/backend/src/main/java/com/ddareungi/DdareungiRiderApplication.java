package com.ddareungi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class DdareungiRiderApplication {
    public static void main(String[] args) {
        SpringApplication.run(DdareungiRiderApplication.class, args);
    }
}
