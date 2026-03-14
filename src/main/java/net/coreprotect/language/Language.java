package net.coreprotect.language;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Language {
    private static final String DEFAULT_BUNDLE = "lang/en.yml";
    private static final ConcurrentHashMap<Phrase, String> phrases = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Phrase, String> userPhrases = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Phrase, String> translatedPhrases = new ConcurrentHashMap<>();

    static {
        loadPhrases();
    }

    private Language() {
    }

    static String getPhrase(Phrase phrase) {
        return phrases.get(phrase);
    }

    static String getUserPhrase(Phrase phrase) {
        return userPhrases.get(phrase);
    }

    static String getTranslatedPhrase(Phrase phrase) {
        return translatedPhrases.get(phrase);
    }

    public static void loadPhrases() {
        phrases.clear();
        userPhrases.clear();
        translatedPhrases.clear();

        loadYamlInto(phrases, DEFAULT_BUNDLE);
        userPhrases.putAll(phrases);
        translatedPhrases.putAll(phrases);

        String locale = normalizeLocale(System.getProperty("coreprotect.language", Locale.getDefault().toLanguageTag()));
        for (String bundle : localizedBundles(locale)) {
            loadYamlInto(translatedPhrases, bundle);
        }
    }

    private static void loadYamlInto(ConcurrentHashMap<Phrase, String> target, String resourcePath) {
        try (InputStream stream = Language.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return;
            }

            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Object loaded = yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map<?, ?> entries)) {
                return;
            }

            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }

                try {
                    Phrase phrase = Phrase.valueOf(String.valueOf(entry.getKey()).trim().toUpperCase(Locale.ROOT));
                    target.put(phrase, String.valueOf(entry.getValue()));
                }
                catch (IllegalArgumentException ignored) {
                }
            }
        }
        catch (Exception ignored) {
        }
    }

    private static List<String> localizedBundles(String locale) {
        List<String> bundles = new ArrayList<>();
        String normalized = normalizeLocale(locale);
        String language = normalized;
        int separator = normalized.indexOf('-');
        bundles.add("lang/" + normalized + ".yml");
        if (separator > 0) {
            language = normalized.substring(0, separator);
            if (!language.equals(normalized)) {
                bundles.add("lang/" + language + ".yml");
            }
        }
        return bundles;
    }

    private static String normalizeLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en";
        }
        return raw.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
