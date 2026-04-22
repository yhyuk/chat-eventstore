package com.example.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChatEventStoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatEventStoreApplication.class, args);
    }
}
