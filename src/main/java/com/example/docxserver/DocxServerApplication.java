package com.example.docxserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DocxServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocxServerApplication.class, args);
    }

}
