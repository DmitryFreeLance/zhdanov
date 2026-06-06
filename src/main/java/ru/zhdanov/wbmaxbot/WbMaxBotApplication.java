package ru.zhdanov.wbmaxbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class WbMaxBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(WbMaxBotApplication.class, args);
    }
}
