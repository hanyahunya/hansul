package com.hansul.recipe.infrastructure.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Routes a shared link to the policy of its source; each policy then applies its own exact rules. */
@Component
public class RecipeSourcePolicy {

    private static final String HUNDRED00_RECIPE_DOMAIN = "10000recipe.com";
    private static final Map<String, String> UNSUPPORTED_VIDEO_PLATFORMS = Map.of(
            "instagram.com", "Instagram",
            "tiktok.com", "TikTok",
            "tv.naver.com", "Naver TV",
            "naver.me", "Naver TV",
            "facebook.com", "Facebook",
            "fb.watch", "Facebook"
    );

    private final Hundred00RecipeUrlPolicy hundred00RecipeUrlPolicy;
    private final YouTubeUrlPolicy youTubeUrlPolicy;

    public RecipeSourcePolicy(
            Hundred00RecipeUrlPolicy hundred00RecipeUrlPolicy,
            YouTubeUrlPolicy youTubeUrlPolicy
    ) {
        this.hundred00RecipeUrlPolicy = hundred00RecipeUrlPolicy;
        this.youTubeUrlPolicy = youTubeUrlPolicy;
    }

    public CanonicalRecipeUrl canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRecipeUrlException("Recipe URL is required");
        }

        var host = hostOf(rawUrl.trim());
        if (isDomainOrSubdomain(host, HUNDRED00_RECIPE_DOMAIN)) {
            return hundred00RecipeUrlPolicy.canonicalize(rawUrl);
        }
        if (YouTubeUrlPolicy.isYouTubeHost(host)) {
            return youTubeUrlPolicy.canonicalize(rawUrl);
        }
        for (var platform : UNSUPPORTED_VIDEO_PLATFORMS.entrySet()) {
            if (isDomainOrSubdomain(host, platform.getKey())) {
                throw new UnsupportedVideoPlatformException(platform.getValue());
            }
        }
        throw new InvalidRecipeUrlException("Only 10000recipe recipe pages and YouTube videos are supported");
    }

    private static String hostOf(String url) {
        final URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new InvalidRecipeUrlException("Recipe URL is malformed");
        }
        if (uri.getHost() == null) {
            throw new InvalidRecipeUrlException("Recipe URL is malformed");
        }
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean isDomainOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }
}
