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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class VoiceCallFollowUpService {

    private static final Logger log = LoggerFactory.getLogger(VoiceCallFollowUpService.class);
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
        this.executorService = Executors.newFixedThreadPool(2, new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "voice-followup-" + index.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        });
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
        if (!callResult.success()) {
            log.warn("Voice call did not start successfully. chatId={}, phone={}, provider={}, details={}",
                    chatId, maskPhone(phoneNumber), callResult.provider(), truncate(callResult.details()));
            maxMessagingService.sendToChat(chatId,
                    maxBotUiService.buildVoiceCallTranscriptionMessage(phoneNumber, "failed", fallbackText));
            return;
        }

        if (!"exolve".equalsIgnoreCase(callResult.provider())) {
            log.info("Voice call follow-up skipped for non-Exolve provider. chatId={}, phone={}, provider={}",
                    chatId, maskPhone(phoneNumber), callResult.provider());
            maxMessagingService.sendToChat(chatId,
                    maxBotUiService.buildVoiceCallTranscriptionMessage(phoneNumber, "completed", fallbackText));
            return;
        }

        String callId = callResult.externalId();
        if (callId == null || callId.isBlank()) {
            log.warn("Voice call follow-up cannot continue without callId. chatId={}, phone={}",
                    chatId, maskPhone(phoneNumber));
            maxMessagingService.sendToChat(chatId,
                    maxBotUiService.buildVoiceCallTranscriptionMessage(phoneNumber, "unknown", fallbackText));
            return;
        }

        try {
            String finalStatus = waitForFinalCallStatus(callId);
            log.info("Exolve final call status received. chatId={}, phone={}, callId={}, status={}",
                    chatId, maskPhone(phoneNumber), callId, finalStatus);
            if (!TRANSCRIPT_POSSIBLE_STATUSES.contains(finalStatus)) {
                log.warn("Exolve transcription skipped because final status is not eligible. chatId={}, phone={}, callId={}, status={}",
                        chatId, maskPhone(phoneNumber), callId, finalStatus);
                maxMessagingService.sendToChat(chatId,
                        maxBotUiService.buildVoiceCallTranscriptionMessage(
                                phoneNumber,
                                finalStatus,
                                "Расшифровка недоступна. Финальный статус звонка: " + finalStatus
                        ));
                return;
            }

            String transcription = waitForTranscription(callId);
            if (transcription == null || transcription.isBlank()) {
                log.warn("Exolve transcription not available yet, sending fallback text. chatId={}, phone={}, callId={}",
                        chatId, maskPhone(phoneNumber), callId);
                transcription = "Расшифровка пока недоступна. Исходный текст звонка:\n" + fallbackText;
            } else {
                log.info("Exolve transcription received. chatId={}, phone={}, callId={}, length={}",
                        chatId, maskPhone(phoneNumber), callId, transcription.length());
            }
            maxMessagingService.sendToChat(chatId,
                    maxBotUiService.buildVoiceCallTranscriptionMessage(phoneNumber, finalStatus, transcription));
        } catch (Exception e) {
            log.warn("Failed to fetch Exolve call follow-up. chatId={}, phone={}, callId={}, error={}",
                    chatId, maskPhone(phoneNumber), callId, e.getMessage(), e);
            maxMessagingService.sendToChat(chatId,
                    maxBotUiService.buildVoiceCallTranscriptionMessage(
                            phoneNumber,
                            "unknown",
                            "Не удалось получить расшифровку звонка. Исходный текст звонка:\n" + fallbackText
                    ));
        }
    }

    private String waitForFinalCallStatus(String callId) throws IOException, InterruptedException {
        String status = "unknown";
        for (int attempt = 0; attempt < 24; attempt++) {
            JsonNode body = postJson(
                    properties.getTelephony().getExolve().getBaseUrl() + "/call/v1/GetInfo",
                    Map.of("call_id", callId)
            );
            status = body.path("status").asText("unknown");
            log.info("Exolve GetInfo poll. callId={}, attempt={}/24, status={}, body={}",
                    callId, attempt + 1, status, truncate(body.toString()));
            if (TERMINAL_STATUSES.contains(status)) {
                return status;
            }
            Thread.sleep(5000L);
        }
        return status;
    }

    private String waitForTranscription(String callId) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 12; attempt++) {
            JsonNode body = postJson(
                    properties.getTelephony().getExolve().getBaseUrl() + "/statistics/call-record/v1/GetTranscribation",
                    Map.of("uid", parseUid(callId))
            );
            log.info("Exolve GetTranscribation poll. callId={}, attempt={}/12, body={}",
                    callId, attempt + 1, truncate(body.toString()));
            String text = extractTranscription(body);
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

    private Object parseUid(String callId) {
        if (callId.matches("\\d+")) {
            return new BigInteger(callId);
        }
        return callId;
    }

    private String extractTranscription(JsonNode body) {
        JsonNode chunksNode = body.path("chunks");
        List<Chunk> chunks = new ArrayList<>();

        if (chunksNode.isArray()) {
            for (JsonNode item : chunksNode) {
                addChunk(chunks, item);
            }
        } else if (chunksNode.isObject()) {
            if (chunksNode.has("text")) {
                addChunk(chunks, chunksNode);
            } else if (chunksNode.has("chunks") && chunksNode.path("chunks").isArray()) {
                for (JsonNode item : chunksNode.path("chunks")) {
                    addChunk(chunks, item);
                }
            }
        }

        if (chunks.isEmpty()) {
            JsonNode transcribationNode = body.path("transcribation");
            if (transcribationNode.isArray()) {
                for (JsonNode item : transcribationNode) {
                    addChunk(chunks, item);
                }
            }
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
}
