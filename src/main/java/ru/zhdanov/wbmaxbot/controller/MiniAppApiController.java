package ru.zhdanov.wbmaxbot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.zhdanov.wbmaxbot.model.MiniAppPrincipal;
import ru.zhdanov.wbmaxbot.service.MaxMessagingService;
import ru.zhdanov.wbmaxbot.service.MaxMiniAppAuthService;
import ru.zhdanov.wbmaxbot.service.MiniAppSessionService;
import ru.zhdanov.wbmaxbot.service.WbAccountService;
import ru.zhdanov.wbmaxbot.service.WbLoginFlowService;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/miniapp")
public class MiniAppApiController {

    private final MaxMiniAppAuthService maxMiniAppAuthService;
    private final MiniAppSessionService miniAppSessionService;
    private final MaxMessagingService maxMessagingService;
    private final WbAccountService wbAccountService;
    private final WbLoginFlowService wbLoginFlowService;

    public MiniAppApiController(MaxMiniAppAuthService maxMiniAppAuthService,
                                MiniAppSessionService miniAppSessionService,
                                MaxMessagingService maxMessagingService,
                                WbAccountService wbAccountService,
                                WbLoginFlowService wbLoginFlowService) {
        this.maxMiniAppAuthService = maxMiniAppAuthService;
        this.miniAppSessionService = miniAppSessionService;
        this.maxMessagingService = maxMessagingService;
        this.wbAccountService = wbAccountService;
        this.wbLoginFlowService = wbLoginFlowService;
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> createSession(@RequestBody Map<String, String> payload) {
        MiniAppPrincipal principal = maxMiniAppAuthService.validate(payload.get("initData"));
        maxMessagingService.subscribe(principal.chatId(), principal.userId(), "MAX chat " + principal.chatId(), "dialog");
        String sessionToken = miniAppSessionService.create(principal);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "sessionToken", sessionToken,
                "chatId", principal.chatId(),
                "userId", principal.userId(),
                "firstName", principal.firstName()
        ));
    }

    @GetMapping("/state")
    public ResponseEntity<Map<String, Object>> getState(@RequestParam("sessionToken") String sessionToken) {
        MiniAppPrincipal principal = miniAppSessionService.getRequired(sessionToken);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "accounts", wbAccountService.listAccounts(principal.chatId())
        ));
    }

    @PostMapping("/wb-auth/start")
    public ResponseEntity<Map<String, Object>> startAuth(@RequestBody Map<String, String> payload) {
        MiniAppPrincipal principal = miniAppSessionService.getRequired(payload.get("sessionToken"));
        WbLoginFlowService.StartedAuth startedAuth = wbLoginFlowService.start(payload.get("phoneNumber"));
        return ResponseEntity.ok(Map.of(
                "success", true,
                "chatId", principal.chatId(),
                "flowId", startedAuth.flowId(),
                "phoneNumber", startedAuth.normalizedPhone(),
                "message", startedAuth.message()
        ));
    }

    @PostMapping("/wb-auth/confirm")
    public ResponseEntity<Map<String, Object>> confirmAuth(@RequestBody Map<String, String> payload) {
        MiniAppPrincipal principal = miniAppSessionService.getRequired(payload.get("sessionToken"));
        String storageStateJson = wbLoginFlowService.confirm(payload.get("flowId"), payload.get("code"));
        wbAccountService.attachAccount(principal.chatId(), payload.get("phoneNumber"), storageStateJson);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "accounts", wbAccountService.listAccounts(principal.chatId())
        ));
    }

    @PostMapping("/accounts/{accountId}/enabled")
    public ResponseEntity<Map<String, Object>> updateEnabled(@PathVariable long accountId,
                                                             @RequestBody Map<String, Object> payload) {
        MiniAppPrincipal principal = miniAppSessionService.getRequired((String) payload.get("sessionToken"));
        boolean enabled = Boolean.TRUE.equals(payload.get("enabled"));
        wbAccountService.setEnabled(principal.chatId(), accountId, enabled);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "accounts", wbAccountService.listAccounts(principal.chatId())
        ));
    }

    @DeleteMapping("/accounts/{accountId}")
    public ResponseEntity<Map<String, Object>> unlinkAccount(@PathVariable long accountId,
                                                             @RequestParam("sessionToken") String sessionToken) {
        MiniAppPrincipal principal = miniAppSessionService.getRequired(sessionToken);
        wbAccountService.unlink(principal.chatId(), accountId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "accounts", wbAccountService.listAccounts(principal.chatId())
        ));
    }
}
