package com.example.docxserver.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/docx/**")
                .addResourceLocations("classpath:/docx/"); // 对应 src/main/resources/docx

        registry.addResourceHandler("/lib/**")
                .addResourceLocations("classpath:/docx/lib/");
    }
}
