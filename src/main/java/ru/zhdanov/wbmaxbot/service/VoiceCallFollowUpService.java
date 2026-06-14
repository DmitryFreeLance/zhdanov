package ru.zhdanov.wbmaxbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;
import ru.zhdanov.wbmaxbot.model.VoiceCallResult;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class VoiceCallFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallFollowUpService.class);
    private static final DateTimeFormatter EXOLVE_RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME;
    private static final Set<String> TERMINAL_STATUSES = Set.of(
            "completed", "no_answer", "canceled", "busy", "rejected", "failed", "modified"
    );
    private static final Set<String> TRANSCRIPT_POSSIBLE_STATUSES = Set.of(
            "completed", "play_audio_stop", "talk", "answered"
    );

    private final AppProperties properties;
    private final MaxMessagingService maxMessagingService;
    private final MaxBotUiService maxBotUiService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final ExecutorService executorService;
    private final Set<Long> activeChatIds;

    public VoiceCallFollowUpService(AppProperties properties,
                                    MaxMessagingService maxMessagingService,
                                    MaxBotUiService maxBotUiService,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.maxMessagingService = maxMessagingService;
        this.maxBotUiService = maxBotUiService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.activeChatIds = ConcurrentHashMap.newKeySet();
        int followUpThreads = Math.max(2, properties.getTelephony().getFollowUpThreads());
        this.executorService = Executors.newFixedThreadPool(followUpThreads, new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "voice-followup-" + index.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
        log.info("Voice call follow-up executor initialized with {} threads", followUpThreads);
    }

    public boolean tryBeginCallFlow(long chatId) {
        return activeChatIds.add(chatId);
    }

    public boolean isCallFlowActive(long chatId) {
        return activeChatIds.contains(chatId);
    }

    public void sendCallResultAsync(long chatId, String phoneNumber, VoiceCallResult callResult, String fallbackText) {
        log.info("Scheduling voice call follow-up. chatId={}, phone={}, provider={}, success={}, callId={}",
                chatId, maskPhone(phoneNumber), callResult.provider(), callResult.success(), callResult.externalId());
        executorService.submit(() -> doSendCallResult(chatId, phoneNumber, callResult, fallbackText));
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private void doSendCallResult(long chatId, String phoneNumber, VoiceCallResult callResult, String fallbackText) {
        boolean hasFallbackText = fallbackText != null && !fallbackText.isBlank();
        try {
            if (!callResult.success()) {
                log.warn("Voice call did not start successfully. chatId={}, phone={}, provider={}, details={}",
                        chatId, maskPhone(phoneNumber), callResult.provider(), truncate(callResult.details()));
                maxMessagingService.sendToChat(chatId,
                        maxBotUiService.buildVoiceCallFollowUpMessage(
                                phoneNumber,
                                "failed",
                                hasFallbackText ? "Текст звонка" : "Детали звонка",
                                hasFallbackText ? fallbackText : "Не удалось запустить звонок."
                        ));
                return;
            }

            if (!"exolve".equalsIgnoreCase(callResult.provider())) {
                log.info("Voice call follow-up skipped for non-Exolve provider. chatId={}, phone={}, provider={}",
                        chatId, maskPhone(phoneNumber), callResult.provider());
                maxMessagingService.sendToChat(chatId,
                        maxBotUiService.buildVoiceCallFollowUpMessage(
                                phoneNumber,
                                "completed",
                                hasFallbackText ? "Текст звонка" : "Детали звонка",
                                hasFallbackText ? fallbackText : "Звонок завершён."
                        ));
                return;
            }

            String callId = callResult.externalId();
            if (callId == null || callId.isBlank()) {
                log.warn("Voice call follow-up cannot continue without callId. chatId={}, phone={}",
                        chatId, maskPhone(phoneNumber));
                maxMessagingService.sendToChat(chatId,
                        maxBotUiService.buildVoiceCallFollowUpMessage(
                                phoneNumber,
                                "unknown",
                                hasFallbackText ? "Текст звонка" : "Детали звонка",
                                hasFallbackText ? fallbackText : "Не удалось определить идентификатор звонка."
                        ));
                return;
            }

            CallInfo finalCallInfo = waitForFinalCallInfo(callId);
            String finalStatus = finalCallInfo.status();
            log.info("Exolve final call status received. chatId={}, phone={}, callId={}, status={}",
                    chatId, maskPhone(phoneNumber), callId, finalStatus);
            if (!TRANSCRIPT_POSSIBLE_STATUSES.contains(finalStatus)) {
                log.warn("Exolve transcription skipped because final status is not eligible. chatId={}, phone={}, callId={}, status={}",
                        chatId, maskPhone(phoneNumber), callId, finalStatus);
                maxMessagingService.sendToChat(chatId,
                        maxBotUiService.buildVoiceCallFollowUpMessage(
                                phoneNumber,
                                finalStatus,
                                "Детали звонка",
                                "Расшифровка недоступна. Финальный статус звонка: " + finalStatus
                        ));
                return;
            }

            String transcription = waitForTranscription(callId, finalCallInfo);
            if (transcription == null || transcription.isBlank()) {
                log.warn("Exolve transcription not available yet, sending fallback text. chatId={}, phone={}, callId={}",
                        chatId, maskPhone(phoneNumber), callId);
                maxMessagingService.sendToChat(chatId,
                        maxBotUiService.buildVoiceCallFollowUpMessage(
                                phoneNumber,
                                finalStatus,
                                hasFallbackText ? "Текст звонка" : "Детали звонка",
                                hasFallbackText ? fallbackText : "Расшифровка пока недоступна."
                        ));
            } else {
                log.info("Exolve transcription received. chatId={}, phone={}, callId={}, length={}",
                        chatId, maskPhone(phoneNumber), callId, transcription.length());
                maxMessagingService.sendToChat(chatId,
                        maxBotUiService.buildVoiceCallTranscriptionMessage(phoneNumber, finalStatus, transcription));
            }
        } catch (Exception e) {
            log.warn("Failed to fetch Exolve call follow-up. chatId={}, phone={}, callId={}, error={}",
                    chatId, maskPhone(phoneNumber), callResult.externalId(), e.getMessage(), e);
            maxMessagingService.sendToChat(chatId,
                    maxBotUiService.buildVoiceCallFollowUpMessage(
                            phoneNumber,
                            "unknown",
                            hasFallbackText ? "Текст звонка" : "Детали звонка",
                            hasFallbackText ? fallbackText : "Не удалось получить данные по звонку."
                    ));
        } finally {
            activeChatIds.remove(chatId);
        }
    }

    private CallInfo waitForFinalCallInfo(String callId) throws IOException, InterruptedException {
        CallInfo lastInfo = new CallInfo(callId, "unknown", null, null, null, null, null);
        for (int attempt = 0; attempt < 24; attempt++) {
            JsonNode body = postJson(
                    properties.getTelephony().getExolve().getBaseUrl() + "/call/v1/GetInfo",
                    Map.of("call_id", callId)
            );
            String status = body.path("status").asText("unknown");
            lastInfo = new CallInfo(
                    callId,
                    status,
                    textOrNull(body, "created"),
                    textOrNull(body, "started"),
                    textOrNull(body, "ended"),
                    textOrNull(body, "source"),
                    textOrNull(body, "destination")
            );
            log.info("Exolve GetInfo poll. callId={}, attempt={}/24, status={}, body={}",
                    callId, attempt + 1, status, truncate(body.toString()));
            if (TERMINAL_STATUSES.contains(status)) {
                return lastInfo;
            }
            Thread.sleep(5000L);
        }
        return lastInfo;
    }

    private String waitForTranscription(String callId, CallInfo callInfo) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 18; attempt++) {
            JsonNode body = postJsonOrNullOnNotFound(
                    properties.getTelephony().getExolve().getBaseUrl() + "/statistics/call-record/v1/GetTranscribation",
                    Map.of("uid", parseUid(callId))
            );
            if (body == null) {
                log.info("Exolve GetTranscribation poll. callId={}, attempt={}/18, result=not-found-yet",
                        callId, attempt + 1);
                Thread.sleep(5000L);
                continue;
            }
            log.info("Exolve GetTranscribation poll. callId={}, attempt={}/18, body={}",
                    callId, attempt + 1, truncate(body.toString()));
            String text = extractTranscription(body);
            if (text != null && !text.isBlank()) {
                return text;
            }
            Thread.sleep(5000L);
        }
        return waitForTranscriptionFromList(callId, callInfo);
    }

    private String waitForTranscriptionFromList(String callId, CallInfo callInfo) throws IOException, InterruptedException {
        OffsetDateTime baseTime = parseExolveTime(callInfo.endedAt());
        if (baseTime == null) {
            baseTime = parseExolveTime(callInfo.startedAt());
        }
        if (baseTime == null) {
            baseTime = parseExolveTime(callInfo.createdAt());
        }
        if (baseTime == null) {
            baseTime = OffsetDateTime.now(ZoneOffset.UTC);
        }

        OffsetDateTime dateFrom = baseTime.minusMinutes(15);
        OffsetDateTime dateTo = baseTime.plusMinutes(15);

        for (int attempt = 0; attempt < 12; attempt++) {
            JsonNode body = postJson(
                    properties.getTelephony().getExolve().getBaseUrl() + "/statistics/call-record/v1/GetTranscribationsList",
                    Map.of(
                            "date_from", dateFrom.toString(),
                            "date_to", dateTo.toString(),
                            "limit", 100,
                            "offset", 0
                    )
            );
            log.info("Exolve GetTranscribationsList poll. callId={}, attempt={}/12, body={}",
                    callId, attempt + 1, truncate(body.toString()));
            String text = extractMatchingTranscriptionFromList(body, callInfo);
            if (text != null && !text.isBlank()) {
                return text;
            }
            Thread.sleep(5000L);
        }
        return null;
    }

    private JsonNode postJson(String url, Map<String, Object> payload) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + properties.getTelephony().getExolve().getApiKey().trim())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Exolve HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode postJsonOrNullOnNotFound(String url, Map<String, Object> payload) throws IOException, InterruptedException {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + properties.getTelephony().getExolve().getApiKey().trim())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Exolve HTTP " + response.statusCode() + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private Object parseUid(String callId) {
        if (callId.matches("\\d+")) {
            return new BigInteger(callId);
        }
        return callId;
    }

    private String textOrNull(JsonNode body, String fieldName) {
        JsonNode node = body.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private OffsetDateTime parseExolveTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value, EXOLVE_RFC_1123);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractMatchingTranscriptionFromList(JsonNode body, CallInfo callInfo) {
        JsonNode list = body.path("transcribation");
        if (!list.isArray()) {
            return null;
        }

        String sourceDigits = digits(callInfo.source());
        String destinationDigits = digits(callInfo.destination());

        for (JsonNode item : list) {
            String numberA = digits(textOrNull(item, "number_a"));
            String numberB = digits(textOrNull(item, "number_b"));
            String redirectNumber = digits(textOrNull(item, "redirect_number"));
            boolean matchesDirect = matchesNumber(numberA, sourceDigits) && matchesNumber(numberB, destinationDigits);
            boolean matchesRedirect = matchesNumber(numberA, sourceDigits) && matchesNumber(redirectNumber, destinationDigits);
            boolean matchesReverse = matchesNumber(numberA, destinationDigits) && matchesNumber(numberB, sourceDigits);
            if (!matchesDirect && !matchesRedirect && !matchesReverse) {
                continue;
            }

            String text = extractTranscription(item);
            if (text != null && !text.isBlank()) {
                log.info("Matched Exolve transcription from list. callId={}, uid={}, numberA={}, numberB={}, redirectNumber={}",
                        callInfo.callId(), textOrNull(item, "uid"), numberA, numberB, redirectNumber);
                return text;
            }
        }
        return null;
    }

    private boolean matchesNumber(String left, String right) {
        return left != null && right != null && !left.isBlank() && !right.isBlank() && left.equals(right);
    }

    private String digits(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }

    private String extractTranscription(JsonNode body) {
        JsonNode chunksNode = body.path("chunks");
        List<Chunk> chunks = new ArrayList<>();

        collectChunks(chunks, chunksNode);

        JsonNode transcribationNode = body.path("transcribation");
        if (chunks.isEmpty() && !transcribationNode.isMissingNode() && !transcribationNode.isNull()) {
            collectChunks(chunks, transcribationNode);
        }

        chunks.sort(Comparator.comparingLong(Chunk::startTime));
        if (chunks.isEmpty()) {
            return null;
        }

        StringBuilder result = new StringBuilder();
        for (Chunk chunk : chunks) {
            if (!result.isEmpty()) {
                result.append('\n');
            }
            result.append(chunk.channelTag() == 1 ? "Сторона 1" : "Сторона 2")
                    .append(": ")
                    .append(chunk.text());
        }
        return result.toString().trim();
    }

    private void collectChunks(List<Chunk> chunks, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                collectChunks(chunks, item);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        if (node.has("text")) {
            addChunk(chunks, node);
        }
        if (node.has("chunks")) {
            collectChunks(chunks, node.path("chunks"));
        }
        if (node.has("transcribation")) {
            collectChunks(chunks, node.path("transcribation"));
        }
    }

    private void addChunk(List<Chunk> chunks, JsonNode item) {
        String text = item.path("text").asText("").trim();
        if (text.isBlank()) {
            return;
        }
        long startTime = parseTimeNode(item.path("start_time"));
        long channelTag = item.path("channel_tag").asLong(0);
        chunks.add(new Chunk(channelTag, text, startTime));
    }

    private long parseTimeNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0L;
        }
        if (node.isNumber()) {
            return node.asLong(0L);
        }
        if (node.isObject()) {
            return node.path("seconds").asLong(0L);
        }
        return 0L;
    }

    private record Chunk(long channelTag, String text, long startTime) {
    }

    private String maskPhone(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return value;
        }
        return "+" + digits.charAt(0) + "***" + digits.substring(digits.length() - 4);
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 800 ? value.substring(0, 800) + "..." : value;
    }

    private record CallInfo(String callId,
                            String status,
                            String createdAt,
                            String startedAt,
                            String endedAt,
                            String source,
                            String destination) {
    }
}
