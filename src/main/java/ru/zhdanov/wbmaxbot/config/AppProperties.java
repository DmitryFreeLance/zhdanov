package ru.zhdanov.wbmaxbot.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @NotBlank
    private String mode = "run";

    @NotBlank
    private String zoneId = "Asia/Novosibirsk";

    @NotNull
    private Path databasePath = Path.of("./data/wb-max-bot.db");

    @NotNull
    private Scheduler scheduler = new Scheduler();

    @NotNull
    private Wildberries wildberries = new Wildberries();

    @NotNull
    private Max max = new Max();

    @NotNull
    private Alert alert = new Alert();

    @NotNull
    private Telephony telephony = new Telephony();

    @NotNull
    private Admin admin = new Admin();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public Path getDatabasePath() {
        return databasePath;
    }

    public void setDatabasePath(Path databasePath) {
        this.databasePath = databasePath;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    public Wildberries getWildberries() {
        return wildberries;
    }

    public void setWildberries(Wildberries wildberries) {
        this.wildberries = wildberries;
    }

    public Max getMax() {
        return max;
    }

    public void setMax(Max max) {
        this.max = max;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert;
    }

    public Telephony getTelephony() {
        return telephony;
    }

    public void setTelephony(Telephony telephony) {
        this.telephony = telephony;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public static class Scheduler {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofMinutes(1);
        private Duration initialDelay = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }
    }

    public static class Wildberries {
        @NotBlank
        private String reportUrl = "https://logistics.wildberries.ru/reports/remainders/last-mile/chart";

        private boolean headless = true;
        private String browserExecutablePath;
        private Duration timeout = Duration.ofSeconds(45);
        private Duration bootstrapTimeout = Duration.ofMinutes(30);
        @NotNull
        private Path storageStatePath = Path.of("./data/wb-storage-state.json");

        public String getReportUrl() {
            return reportUrl;
        }

        public void setReportUrl(String reportUrl) {
            this.reportUrl = reportUrl;
        }

        public boolean isHeadless() {
            return headless;
        }

        public void setHeadless(boolean headless) {
            this.headless = headless;
        }

        public String getBrowserExecutablePath() {
            return browserExecutablePath;
        }

        public void setBrowserExecutablePath(String browserExecutablePath) {
            this.browserExecutablePath = browserExecutablePath;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getBootstrapTimeout() {
            return bootstrapTimeout;
        }

        public void setBootstrapTimeout(Duration bootstrapTimeout) {
            this.bootstrapTimeout = bootstrapTimeout;
        }

        public Path getStorageStatePath() {
            return storageStatePath;
        }

        public void setStorageStatePath(Path storageStatePath) {
            this.storageStatePath = storageStatePath;
        }
    }

    public static class Max {
        private boolean enabled;
        @NotBlank
        private String baseUrl = "https://platform-api.max.ru";
        private boolean longPollingEnabled = true;
        private Duration longPollingTimeout = Duration.ofSeconds(30);
        private int longPollingLimit = 100;
        private boolean autoRegisterWebhook;
        private String publicWebhookUrl;
        private String token;
        private String webhookSecret;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public boolean isLongPollingEnabled() {
            return longPollingEnabled;
        }

        public void setLongPollingEnabled(boolean longPollingEnabled) {
            this.longPollingEnabled = longPollingEnabled;
        }

        public Duration getLongPollingTimeout() {
            return longPollingTimeout;
        }

        public void setLongPollingTimeout(Duration longPollingTimeout) {
            this.longPollingTimeout = longPollingTimeout;
        }

        public int getLongPollingLimit() {
            return longPollingLimit;
        }

        public void setLongPollingLimit(int longPollingLimit) {
            this.longPollingLimit = longPollingLimit;
        }

        public boolean isAutoRegisterWebhook() {
            return autoRegisterWebhook;
        }

        public void setAutoRegisterWebhook(boolean autoRegisterWebhook) {
            this.autoRegisterWebhook = autoRegisterWebhook;
        }

        public String getPublicWebhookUrl() {
            return publicWebhookUrl;
        }

        public void setPublicWebhookUrl(String publicWebhookUrl) {
            this.publicWebhookUrl = publicWebhookUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }
    }

    public static class Alert {
        private int shkThreshold = 1200;

        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double ratioThreshold = 0.9d;

        private Duration cooldown = Duration.ofMinutes(30);
        private boolean sendReportEachRun = true;
        private int maxRowsInMessage = 50;
        private boolean voiceCallEnabled = false;

        public int getShkThreshold() {
            return shkThreshold;
        }

        public void setShkThreshold(int shkThreshold) {
            this.shkThreshold = shkThreshold;
        }

        public double getRatioThreshold() {
            return ratioThreshold;
        }

        public void setRatioThreshold(double ratioThreshold) {
            this.ratioThreshold = ratioThreshold;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }

        public boolean isSendReportEachRun() {
            return sendReportEachRun;
        }

        public void setSendReportEachRun(boolean sendReportEachRun) {
            this.sendReportEachRun = sendReportEachRun;
        }

        public int getMaxRowsInMessage() {
            return maxRowsInMessage;
        }

        public void setMaxRowsInMessage(int maxRowsInMessage) {
            this.maxRowsInMessage = maxRowsInMessage;
        }

        public boolean isVoiceCallEnabled() {
            return voiceCallEnabled;
        }

        public void setVoiceCallEnabled(boolean voiceCallEnabled) {
            this.voiceCallEnabled = voiceCallEnabled;
        }
    }

    public static class Telephony {
        @NotBlank
        private String provider = "noop";
        private List<String> targetNumbers = new ArrayList<>();
        @NotNull
        private Mango mango = new Mango();
        @NotNull
        private Twilio twilio = new Twilio();
        @NotNull
        private Zadarma zadarma = new Zadarma();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public List<String> getTargetNumbers() {
            return targetNumbers;
        }

        public void setTargetNumbers(List<String> targetNumbers) {
            this.targetNumbers = targetNumbers;
        }

        public Mango getMango() {
            return mango;
        }

        public void setMango(Mango mango) {
            this.mango = mango;
        }

        public Twilio getTwilio() {
            return twilio;
        }

        public void setTwilio(Twilio twilio) {
            this.twilio = twilio;
        }

        public Zadarma getZadarma() {
            return zadarma;
        }

        public void setZadarma(Zadarma zadarma) {
            this.zadarma = zadarma;
        }
    }

    public static class Mango {
        @NotBlank
        private String baseUrl = "https://app.mango-office.ru";
        private String apiKey;
        private String apiSalt;
        private String extension;
        private String fromNumber;
        private String lineNumber;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiSalt() {
            return apiSalt;
        }

        public void setApiSalt(String apiSalt) {
            this.apiSalt = apiSalt;
        }

        public String getExtension() {
            return extension;
        }

        public void setExtension(String extension) {
            this.extension = extension;
        }

        public String getFromNumber() {
            return fromNumber;
        }

        public void setFromNumber(String fromNumber) {
            this.fromNumber = fromNumber;
        }

        public String getLineNumber() {
            return lineNumber;
        }

        public void setLineNumber(String lineNumber) {
            this.lineNumber = lineNumber;
        }
    }

    public static class Twilio {
        @NotBlank
        private String baseUrl = "https://api.twilio.com/2010-04-01";
        private String accountSid;
        private String authToken;
        private String fromNumber;
        private String language = "ru-RU";
        private String voice = "alice";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAccountSid() {
            return accountSid;
        }

        public void setAccountSid(String accountSid) {
            this.accountSid = accountSid;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public String getFromNumber() {
            return fromNumber;
        }

        public void setFromNumber(String fromNumber) {
            this.fromNumber = fromNumber;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }
    }

    public static class Zadarma {
        @NotBlank
        private String baseUrl = "https://api.zadarma.com";
        private String key;
        private String secret;
        private String from;
        private String sip;
        private boolean predicted = true;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getSip() {
            return sip;
        }

        public void setSip(String sip) {
            this.sip = sip;
        }

        public boolean isPredicted() {
            return predicted;
        }

        public void setPredicted(boolean predicted) {
            this.predicted = predicted;
        }
    }

    public static class Admin {
        private String token;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }
    }
}
