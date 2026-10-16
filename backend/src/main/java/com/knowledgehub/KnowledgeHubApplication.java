package com.knowledgehub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KnowledgeHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(KnowledgeHubApplication.class, args);
    }
}
