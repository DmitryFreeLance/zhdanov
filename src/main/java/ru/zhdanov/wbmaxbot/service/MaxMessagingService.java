package ru.zhdanov.wbmaxbot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.client.MaxApiClient;
import ru.zhdanov.wbmaxbot.model.ChatSubscription;
import ru.zhdanov.wbmaxbot.repository.SubscriptionRepository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class MaxMessagingService {

    private static final Logger log = LoggerFactory.getLogger(MaxMessagingService.class);

    private final MaxApiClient maxApiClient;
    private final SubscriptionRepository subscriptionRepository;
    private final ZoneId zoneId;

    public MaxMessagingService(MaxApiClient maxApiClient,
                               SubscriptionRepository subscriptionRepository,
                               ru.zhdanov.wbmaxbot.config.AppProperties properties) {
        this.maxApiClient = maxApiClient;
        this.subscriptionRepository = subscriptionRepository;
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public void subscribe(long chatId, Long userId, String title, String chatType) {
        subscriptionRepository.upsert(chatId, userId, title, chatType, true, now());
    }

    public void deactivate(long chatId) {
        subscriptionRepository.deactivate(chatId, now());
    }

    public int activeChatsCount() {
        return subscriptionRepository.countActive();
    }

    public List<ChatSubscription> activeChats() {
        return subscriptionRepository.findActive();
    }

    public String sendToChat(long chatId, String message) {
        try {
            maxApiClient.sendMessage(chatId, message);
            return "sent";
        } catch (Exception e) {
            log.error("Failed to send MAX message to chat {}", chatId, e);
            return "failed: " + e.getMessage();
        }
    }

    public String sendToActiveChats(List<String> messages) {
        List<ChatSubscription> chats = activeChats();
        if (chats.isEmpty()) {
            return "no-active-chats";
        }

        int successCount = 0;
        int failureCount = 0;
        for (ChatSubscription chat : chats) {
            for (String message : messages) {
                String status = sendToChat(chat.chatId(), message);
                if (status.startsWith("sent")) {
                    successCount++;
                } else {
                    failureCount++;
                }
            }
        }
        return "sent=" + successCount + ", failed=" + failureCount;
    }

    public void sendWelcome(long chatId, String welcomeMessage) {
        sendToChat(chatId, welcomeMessage);
    }

    public void registerWebhookIfNeeded() {
        maxApiClient.registerWebhookIfNeeded();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(zoneId);
    }
}
