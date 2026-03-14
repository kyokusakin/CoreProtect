package net.coreprotect.fabric.service;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateCheckService {
    private static final Pattern TAG_NAME_PATTERN = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final String RELEASES_URL = "https://api.github.com/repos/PlayPro/CoreProtect/releases/latest";
    private static final String TAGS_URL = "https://api.github.com/repos/PlayPro/CoreProtect/tags?per_page=1";

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

    private String fetchLatestVersion() throws IOException, InterruptedException {
        String latest = fetchTagName(RELEASES_URL);
        if (latest != null && !latest.isBlank()) {
            return latest;
        }
        return fetchTagName(TAGS_URL);
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
}
