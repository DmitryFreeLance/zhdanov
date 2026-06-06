package ru.zhdanov.wbmaxbot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.service.MaxUpdateHandler;

import java.util.Map;

@RestController
@RequestMapping("/webhook/max")
public class MaxWebhookController {

    private final AppProperties properties;
    private final MaxUpdateHandler maxUpdateHandler;

    public MaxWebhookController(AppProperties properties, MaxUpdateHandler maxUpdateHandler) {
        this.properties = properties;
        this.maxUpdateHandler = maxUpdateHandler;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> receiveUpdate(
            @RequestHeader(value = "X-Max-Bot-Api-Secret", required = false) String secret,
            @RequestBody JsonNode payload
    ) {
        String configuredSecret = properties.getMax().getWebhookSecret();
        if (configuredSecret != null && !configuredSecret.isBlank() && !configuredSecret.equals(secret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("success", false, "message", "Invalid secret"));
        }

        maxUpdateHandler.handle(payload);
        return ResponseEntity.ok(Map.of("success", true));
    }
}
