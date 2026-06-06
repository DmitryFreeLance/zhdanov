package ru.zhdanov.wbmaxbot.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.zhdanov.wbmaxbot.config.AppProperties;

@Component
@ConditionalOnProperty(name = "app.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class ReportScheduler {

    private final ReportCoordinator reportCoordinator;

    public ReportScheduler(ReportCoordinator reportCoordinator) {
        this.reportCoordinator = reportCoordinator;
    }

    @Scheduled(
            fixedDelayString = "#{@appProperties.scheduler.fixedDelay.toMillis()}",
            initialDelayString = "#{@appProperties.scheduler.initialDelay.toMillis()}"
    )
    public void runScheduledReport() {
        reportCoordinator.executeScheduledRun();
    }
}
