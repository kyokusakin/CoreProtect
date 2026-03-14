package net.coreprotect.fabric.language;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class PhraseService {
    private static final String DEFAULT_YAML_BUNDLE = "lang/en.yml";
    private static final PhraseService INSTANCE = new PhraseService();

    private final Map<String, String> english = new HashMap<>();
    private final Map<String, String> localized = new HashMap<>();

    private PhraseService() {
        load();
    }

    public static PhraseService getInstance() {
        return INSTANCE;
    }

    public synchronized void load() {
        english.clear();
        localized.clear();

        loadYamlInto(english, DEFAULT_YAML_BUNDLE);

        String locale = normalizeLocale(System.getProperty("coreprotect.language", Locale.getDefault().toLanguageTag()));
        for (String bundle : localizedBundles(locale)) {
            loadYamlInto(localized, bundle);
        }
    }

    public String phrase(String key, String fallback, Object... args) {
        String template = resolve(localized, key);
        if (template == null || template.isBlank()) {
            template = resolve(english, key);
        }
        if (template == null || template.isBlank()) {
            template = fallback == null ? key : fallback;
        }
        if (args == null || args.length == 0) {
            return template;
        }
        return applyArguments(template, args);
    }

    private void loadYamlInto(Map<String, String> target, String resourcePath) {
        try (InputStream stream = PhraseService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return;
            }

            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> map)) {
                return;
            }

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                target.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        catch (Exception ignored) {
        }
    }

    private List<String> localizedBundles(String locale) {
        List<String> bundles = new ArrayList<>();
        String normalized = normalizeLocale(locale);
        String language = normalized;
        int separator = normalized.indexOf('-');
        if (separator > 0) {
            language = normalized.substring(0, separator);
            bundles.add("lang/" + normalized + ".yml");
        }
        else {
            bundles.add("lang/" + normalized + ".yml");
        }

        if (!language.equals(normalized)) {
            bundles.add("lang/" + language + ".yml");
        }

        return bundles;
    }

    private String resolve(Map<String, String> source, String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String direct = source.get(key);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String masterKey = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
        return source.get(masterKey);
    }

    private String applyArguments(String template, Object... args) {
        String output = template;
        for (int i = 0; i < args.length; i++) {
            output = output.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return output;
    }

    private String normalizeLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en";
        }
        return raw.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
