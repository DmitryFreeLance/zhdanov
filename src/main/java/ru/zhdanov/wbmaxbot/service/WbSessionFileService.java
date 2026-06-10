package ru.zhdanov.wbmaxbot.service;

import org.springframework.stereotype.Service;
import ru.zhdanov.wbmaxbot.config.AppProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class WbSessionFileService {

    private final Path importDirectory;

    public WbSessionFileService(AppProperties properties) {
        this.importDirectory = properties.getWildberries()
                .getStorageStatePath()
                .toAbsolutePath()
                .normalize()
                .getParent()
                .resolve("wb-session-imports");
    }

    public Optional<String> findStorageStateJson(String normalizedPhoneNumber) {
        for (Path candidate : candidatePaths(normalizedPhoneNumber)) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                return Optional.of(Files.readString(candidate));
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось прочитать файл WB-сессии: " + candidate, e);
            }
        }
        return Optional.empty();
    }

    public Path preferredPath(String normalizedPhoneNumber) {
        String digits = digitsOnly(normalizedPhoneNumber);
        return importDirectory.resolve(digits + ".json");
    }

    public Path importDirectory() {
        return importDirectory;
    }

    private Set<Path> candidatePaths(String normalizedPhoneNumber) {
        String digits = digitsOnly(normalizedPhoneNumber);
        String localDigits = digits.length() == 11 ? digits.substring(1) : digits;
        Set<Path> candidates = new LinkedHashSet<>();
        addCandidate(candidates, digits + ".json");
        addCandidate(candidates, "+" + digits + ".json");
        addCandidate(candidates, localDigits + ".json");
        addCandidate(candidates, digits + ".storage-state.json");
        addCandidate(candidates, localDigits + ".storage-state.json");
        return candidates;
    }

    private void addCandidate(Set<Path> candidates, String fileName) {
        candidates.add(importDirectory.resolve(fileName));
    }

    private String digitsOnly(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }
}
