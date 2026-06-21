package com.oncology.radphys;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync(proxyTargetClass = true)
public class RtVerificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(RtVerificationApplication.class, args);
    }
}
