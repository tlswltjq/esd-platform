package com.stove.download;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Owned products' current published full builds, without local business state. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DownloadServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DownloadServiceApplication.class, args);
    }
}
