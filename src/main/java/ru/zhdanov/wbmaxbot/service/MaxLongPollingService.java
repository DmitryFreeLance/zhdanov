package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.client.MaxApiClient;
import ru.zhdanov.wbmaxbot.config.AppProperties;

@Component
@ConditionalOnProperty(name = {"app.max.enabled", "app.max.long-polling-enabled"}, havingValue = "true")
public class MaxLongPollingService {

    private static final Logger log = LoggerFactory.getLogger(MaxLongPollingService.class);

    private final MaxApiClient maxApiClient;
    private final MaxUpdateHandler maxUpdateHandler;
    private final AppProperties properties;

    private volatile Long marker;

    public MaxLongPollingService(MaxApiClient maxApiClient,
                                 MaxUpdateHandler maxUpdateHandler,
                                 AppProperties properties) {
        this.maxApiClient = maxApiClient;
        this.maxUpdateHandler = maxUpdateHandler;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        log.info("MAX long polling is enabled");
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        try {
            MaxApiClient.MaxUpdatesResponse response = maxApiClient.getUpdates(marker);
            if (response.updates() != null) {
                for (JsonNode update : response.updates()) {
                    maxUpdateHandler.handle(update);
                }
            }
            marker = response.marker();
        } catch (Exception e) {
            log.error("MAX long polling failed", e);
            try {
                Thread.sleep(Math.min(properties.getMax().getLongPollingTimeout().toMillis(), 5000));
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
