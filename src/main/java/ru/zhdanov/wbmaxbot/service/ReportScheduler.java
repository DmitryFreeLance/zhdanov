package ru.zhdanov.wbmaxbot.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.config.AppProperties;

@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ReportScheduler {

    private final AppProperties properties;
    private final ReportCoordinator reportCoordinator;

    public ReportScheduler(AppProperties properties, ReportCoordinator reportCoordinator) {
        this.properties = properties;
        this.reportCoordinator = reportCoordinator;
    }

    @Scheduled(
            fixedDelayString = "${app.scheduler.fixed-delay}",
            initialDelayString = "${app.scheduler.initial-delay}"
    )
    public void runScheduledReport() {
        if (!"run".equalsIgnoreCase(properties.getMode())) {
            return;
        }
        reportCoordinator.executeScheduledRun();
    }
}
