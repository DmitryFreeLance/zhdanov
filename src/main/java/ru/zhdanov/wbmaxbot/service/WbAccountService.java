package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.model.ChatLinkedWbAccount;
import ru.zhdanov.wbmaxbot.model.WbAccount;
import ru.zhdanov.wbmaxbot.repository.ChatWbAccountRepository;
import ru.zhdanov.wbmaxbot.repository.WbAccountRepository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class WbAccountService {

    private final WbAccountRepository wbAccountRepository;
    private final ChatWbAccountRepository chatWbAccountRepository;
    private final ZoneId zoneId;

    public WbAccountService(WbAccountRepository wbAccountRepository,
                            ChatWbAccountRepository chatWbAccountRepository,
                            ru.zhdanov.wbmaxbot.config.AppProperties properties) {
        this.wbAccountRepository = wbAccountRepository;
        this.chatWbAccountRepository = chatWbAccountRepository;
        this.zoneId = ZoneId.of(properties.getZoneId());
    }

    public List<ChatLinkedWbAccount> listAccounts(long chatId) {
        return chatWbAccountRepository.findByChatId(chatId);
    }

    public List<ChatLinkedWbAccount> listEnabledAccounts(long chatId) {
        return chatWbAccountRepository.findEnabledByChatId(chatId);
    }

    public int countAccounts(long chatId) {
        return chatWbAccountRepository.countForChat(chatId);
    }

    public ChatLinkedWbAccount attachAccount(long chatId, String phoneNumber, String storageStateJson) {
        OffsetDateTime now = now();
        WbAccount account = wbAccountRepository.upsertConnected(phoneNumber, storageStateJson, now);
        chatWbAccountRepository.disableAllForChat(chatId, now);
        chatWbAccountRepository.linkAccount(chatId, account.id(), now);
        return listAccounts(chatId).stream()
                .filter(item -> item.accountId() == account.id())
                .findFirst()
                .orElseThrow();
    }

    public void setEnabled(long chatId, long accountId, boolean enabled) {
        OffsetDateTime now = now();
        if (enabled) {
            chatWbAccountRepository.disableAllForChat(chatId, now);
        }
        chatWbAccountRepository.updateEnabled(chatId, accountId, enabled, now);
    }

    public void unlink(long chatId, long accountId) {
        chatWbAccountRepository.unlink(chatId, accountId);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(zoneId);
    }
}
