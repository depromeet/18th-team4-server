package com.readum.logprocessor;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class LogProcessorApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(LogProcessorApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
