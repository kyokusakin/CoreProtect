package net.coreprotect.fabric.service;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateCheckService implements AutoCloseable {
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final String RELEASES_URL = "https://api.github.com/repos/kyokusakin/CoreProtect/releases/latest";

    private final Logger logger;
    private final String currentVersion;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coreprotect-fabric-update-check");
        thread.setDaemon(true);
        return thread;
    });

    private volatile String latestVersion;
    private volatile boolean checked;

    public UpdateCheckService(Logger logger) {
        this.logger = logger;
        this.currentVersion = FabricLoader.getInstance()
            .getModContainer("coreprotect_fabric")
            .map(container -> container.getMetadata().getVersion().getFriendlyString())
            .orElse("unknown");
    }

    public void refreshAsync(boolean enabled) {
        if (!enabled) {
            checked = false;
            latestVersion = null;
            return;
        }

        executor.submit(() -> {
            try {
                latestVersion = fetchLatestVersion();
                checked = true;
            }
            catch (Exception exception) {
                checked = true;
                latestVersion = null;
                if (logger != null) {
                    logger.debug("CoreProtect Fabric update check failed", exception);
                }
            }
        });
    }

    public String currentVersion() {
        return currentVersion;
    }

    public String latestVersion() {
        return latestVersion;
    }

    public boolean checked() {
        return checked;
    }

    public boolean isOutdated() {
        String latest = latestVersion;
        if (!checked || latest == null || latest.isBlank()) {
            return false;
        }

        int[] current = parseVersion(currentVersion);
        int[] remote = parseVersion(latest);
        if (current.length == 0 || remote.length == 0) {
            return false;
        }

        int length = Math.max(current.length, remote.length);
        for (int index = 0; index < length; index++) {
            int a = index < current.length ? current[index] : 0;
            int b = index < remote.length ? remote[index] : 0;
            if (a != b) {
                return a < b;
            }
        }
        return false;
    }

    private String fetchLatestVersion() throws IOException, InterruptedException {
        return fetchTagName(RELEASES_URL);
    }

    private String fetchTagName(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CoreProtectFabric")
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return null;
        }

        Matcher matcher = TAG_NAME_PATTERN.matcher(response.body());
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private static int[] parseVersion(String version) {
        String normalized = version.replaceAll("(?i)^v", "").trim();
        String[] parts = normalized.split("[^0-9]+");
        int[] numbers = new int[parts.length];
        int count = 0;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            numbers[count++] = Integer.parseInt(part);
        }
        return Arrays.copyOf(numbers, count);
    }

    boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
