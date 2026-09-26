package com.readum.infrastructure.book.aladin;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
public class AladinHttpConfig {

    @Bean
    public RestClient aladinRestClient(AladinProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
