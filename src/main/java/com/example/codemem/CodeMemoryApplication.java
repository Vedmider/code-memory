package com.example.codemem;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeMemoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeMemoryApplication.class, args);
    }
}
