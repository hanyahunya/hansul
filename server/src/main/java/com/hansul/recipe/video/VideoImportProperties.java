package com.hansul.recipe.video;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hansul.video-import")
public record VideoImportProperties(
        Gemini gemini,
        YouTube youtube,
        int maxVideoSeconds,
        int dailyLimitPerUser,
        int workerConcurrency,
        Upload upload
) {
    public VideoImportProperties {
        gemini = gemini == null ? new Gemini(null, null, null, null, null, 0, 0, null, false) : gemini;
        youtube = youtube == null ? new YouTube(null, null, null) : youtube;
        maxVideoSeconds = maxVideoSeconds <= 0 ? 1800 : maxVideoSeconds;
        dailyLimitPerUser = dailyLimitPerUser <= 0 ? 10 : dailyLimitPerUser;
        workerConcurrency = workerConcurrency <= 0 ? 2 : workerConcurrency;
        upload = upload == null ? new Upload(null, 0, 0, 0, null, 0, 0, null) : upload;
    }

    /** Video analysis needs a Gemini key and a model name that is safe to put in the request path. */
    public boolean isConfigured() {
        return gemini.isConfigured();
    }

    /**
     * Phone videos (faces, voices, homes) go to Gemini only when the operator states the key is paid tier: on the
     * unpaid tier Google may use inputs to improve its products and have people review them. YouTube videos are
     * public, so any key may analyse them.
     */
    public boolean isConfiguredForUploads() {
        return isConfigured() && gemini.paidTier();
    }

    public record Gemini(
            String apiBaseUrl,
            String apiKey,
            String model,
            String thinkingLevel,
            String mediaResolution,
            int transcriptMaxOutputTokens,
            int recipeMaxOutputTokens,
            Duration requestTimeout,
            boolean paidTier
    ) {
        public static final String DEFAULT_MODEL = "gemini-3.8-flash";
        private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._-]+");
        private static final Pattern THINKING_LEVEL = Pattern.compile("[A-Z_]+");
        private static final Set<String> MEDIA_RESOLUTIONS = Set.of("LOW", "MEDIUM", "HIGH");
        private static final String MEDIA_RESOLUTION_PREFIX = "MEDIA_RESOLUTION_";

        /**
         * {@code thinkingLevel} is kept upper-cased ({@code LOW}) and {@code mediaResolution} as the API enum
         * ({@code MEDIA_RESOLUTION_LOW}); empty means the request leaves the setting out.
         */
        public Gemini {
            apiBaseUrl = baseUrl(apiBaseUrl, "https://generativelanguage.googleapis.com");
            apiKey = apiKey == null ? "" : apiKey.trim();
            model = model == null ? DEFAULT_MODEL : model.trim();
            thinkingLevel = thinkingLevel(thinkingLevel);
            mediaResolution = mediaResolution(mediaResolution);
            transcriptMaxOutputTokens = transcriptMaxOutputTokens <= 0 ? 65536 : transcriptMaxOutputTokens;
            recipeMaxOutputTokens = recipeMaxOutputTokens <= 0 ? 16384 : recipeMaxOutputTokens;
            requestTimeout = isPositive(requestTimeout) ? requestTimeout : Duration.ofSeconds(300);
        }

        public boolean isConfigured() {
            return !apiKey.isEmpty() && MODEL.matcher(model).matches();
        }

        private static String thinkingLevel(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            var level = value.trim().toUpperCase(Locale.ROOT);
            if (!THINKING_LEVEL.matcher(level).matches()) {
                throw new IllegalArgumentException(
                        "hansul.video-import.gemini.thinking-level must be a level name such as LOW or HIGH");
            }
            return level;
        }

        // An unknown value would make Gemini answer 400, which reads as "the video could not be opened".
        private static String mediaResolution(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            var level = value.trim().toUpperCase(Locale.ROOT);
            if (level.startsWith(MEDIA_RESOLUTION_PREFIX)) {
                level = level.substring(MEDIA_RESOLUTION_PREFIX.length());
            }
            if (!MEDIA_RESOLUTIONS.contains(level)) {
                throw new IllegalArgumentException(
                        "hansul.video-import.gemini.media-resolution must be LOW, MEDIUM or HIGH");
            }
            return MEDIA_RESOLUTION_PREFIX + level;
        }
    }

    public record YouTube(
            String apiBaseUrl,
            String oembedBaseUrl,
            String apiKey
    ) {
        public YouTube {
            apiBaseUrl = baseUrl(apiBaseUrl, "https://www.googleapis.com");
            oembedBaseUrl = baseUrl(oembedBaseUrl, "https://www.youtube.com");
            apiKey = apiKey == null ? "" : apiKey.trim();
        }

        /** The Data API adds the description and duration; without a key only oEmbed is asked. */
        public boolean hasApiKey() {
            return !apiKey.isEmpty();
        }
    }

    /**
     * {@code maxTotalBytes} caps the declared size of all unfinished uploads together, {@code minFreeBytes} is the
     * disk space chunks and assembly always leave free, and an upload without a chunk for {@code idleTimeout}
     * gives its place up when a limit is reached.
     */
    public record Upload(
            String directory,
            long maxBytes,
            int chunkBytes,
            int maxActivePerUser,
            Duration ttl,
            long maxTotalBytes,
            long minFreeBytes,
            Duration idleTimeout
    ) {
        public Upload {
            directory = directory == null || directory.isBlank()
                    ? Path.of(System.getProperty("java.io.tmpdir"), "hansul-video-uploads").toString()
                    : directory.trim();
            maxBytes = maxBytes <= 0 ? 314_572_800L : maxBytes;
            chunkBytes = chunkBytes <= 0 ? 4_194_304 : chunkBytes;
            maxActivePerUser = maxActivePerUser <= 0 ? 2 : maxActivePerUser;
            ttl = isPositive(ttl) ? ttl : Duration.ofHours(6);
            maxTotalBytes = maxTotalBytes <= 0 ? 3_221_225_472L : maxTotalBytes;
            minFreeBytes = minFreeBytes <= 0 ? 2_147_483_648L : minFreeBytes;
            idleTimeout = isPositive(idleTimeout) ? idleTimeout : Duration.ofMinutes(5);
        }

        public Path directoryPath() {
            return Path.of(directory);
        }
    }

    private static String baseUrl(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        var trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isPositive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero();
    }
}
