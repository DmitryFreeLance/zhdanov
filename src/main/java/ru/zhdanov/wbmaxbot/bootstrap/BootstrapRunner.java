package ru.zhdanov.wbmaxbot.bootstrap;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.client.MaxApiClient;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.service.WildberriesScraper;

@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    private final AppProperties properties;
    private final WildberriesScraper wildberriesScraper;
    private final MaxApiClient maxApiClient;
    private final ConfigurableApplicationContext applicationContext;

    public BootstrapRunner(AppProperties properties,
                           WildberriesScraper wildberriesScraper,
                           MaxApiClient maxApiClient,
                           ConfigurableApplicationContext applicationContext) {
        this.properties = properties;
        this.wildberriesScraper = wildberriesScraper;
        this.maxApiClient = maxApiClient;
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void registerWebhook() {
        if ("run".equalsIgnoreCase(properties.getMode())) {
            maxApiClient.registerWebhookIfNeeded();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if ("bootstrap-wb-session".equalsIgnoreCase(properties.getMode())) {
            wildberriesScraper.bootstrapSession();
            log.info("WB bootstrap completed. Application will exit.");
            int exitCode = SpringApplication.exit(applicationContext, () -> 0);
            System.exit(exitCode);
        }
    }
}
