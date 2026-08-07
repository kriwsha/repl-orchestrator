package dev.replorch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ReplOrchestratorApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReplOrchestratorApplication.class, args);
    }
}
