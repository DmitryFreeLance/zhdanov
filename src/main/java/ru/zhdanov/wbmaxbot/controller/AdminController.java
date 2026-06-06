package ru.zhdanov.wbmaxbot.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.service.ReportCoordinator;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AppProperties properties;
    private final ReportCoordinator reportCoordinator;

    public AdminController(AppProperties properties, ReportCoordinator reportCoordinator) {
        this.properties = properties;
        this.reportCoordinator = reportCoordinator;
    }

    @PostMapping("/run-now")
    public ResponseEntity<Map<String, Object>> runNow(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false));
        }

        reportCoordinator.executeScheduledRun();
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (!isAuthorized(token)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false));
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "status", reportCoordinator.buildStatusMessage()
        ));
    }

    private boolean isAuthorized(String token) {
        String configured = properties.getAdmin().getToken();
        return configured != null && !configured.isBlank() && configured.equals(token);
    }
}
