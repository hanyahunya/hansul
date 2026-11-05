package com.hansul.recipe.infrastructure.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class Hundred00RecipeUrlPolicy {

    public static final String PROVIDER = "10000recipe";
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "www.10000recipe.com",
            "m.10000recipe.com"
    );
    private static final Pattern PATH_PATTERN = Pattern.compile("^/recipe/([1-9][0-9]{0,19})$");

    public CanonicalRecipeUrl canonicalize(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRecipeUrlException("Recipe URL is required");
        }

        final URI input;
        try {
            input = new URI(rawUrl.trim());
        } catch (URISyntaxException exception) {
            throw new InvalidRecipeUrlException("Recipe URL is malformed");
        }

        var scheme = input.getScheme();
        var host = input.getHost();
        if (scheme == null || !"https".equalsIgnoreCase(scheme)) {
            throw new InvalidRecipeUrlException("Only HTTPS recipe URLs are supported");
        }
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new InvalidRecipeUrlException("Only www.10000recipe.com and m.10000recipe.com are supported");
        }
        if (input.getUserInfo() != null || input.getPort() != -1) {
            throw new InvalidRecipeUrlException("User information and explicit ports are not allowed");
        }

        var matcher = PATH_PATTERN.matcher(input.getRawPath());
        if (!matcher.matches()) {
            throw new InvalidRecipeUrlException("Expected an exact /recipe/{numeric-id} path");
        }

        var recipeId = matcher.group(1);
        var canonical = URI.create("https://www.10000recipe.com/recipe/" + recipeId);
        return new CanonicalRecipeUrl(PROVIDER, recipeId, canonical);
    }
}
