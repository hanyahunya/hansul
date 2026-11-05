package com.hansul.recipe.infrastructure.parser;

import com.hansul.recipe.application.RecipeImportException;
import com.hansul.recipe.domain.ParsedIngredient;
import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.domain.ParsedStep;
import com.hansul.recipe.domain.ParsedVideo;
import com.hansul.recipe.domain.SourceEvidence;
import com.hansul.recipe.domain.SourceEvidence.SourceType;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Hundred00RecipeDocumentParser {

    public static final String PARSER_VERSION = "10000recipe-v2";
    private static final Pattern SERVING_NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern DOM_STEP_ID = Pattern.compile("^stepDiv([1-9][0-9]*)$");
    private static final Pattern NAVER_TV_EMBED_PATH = Pattern.compile("^/embed/([0-9]{1,20})/?$");

    private final JsonMapper jsonMapper;
    private final IngredientTextParser ingredientTextParser;

    public Hundred00RecipeDocumentParser(
            JsonMapper jsonMapper,
            IngredientTextParser ingredientTextParser
    ) {
        this.jsonMapper = jsonMapper;
        this.ingredientTextParser = ingredientTextParser;
    }

    public ParsedRecipe parse(String html, CanonicalRecipeUrl source) {
        var document = Jsoup.parse(html, source.uri().toString());
        var warnings = new ArrayList<String>();
        var jsonCandidate = findJsonLdRecipe(document, warnings);

        var domSteps = readDomSteps(document);
        var domUsed = false;

        String title = null;
        String description = null;
        String yieldText = null;
        String totalTimeText = null;
        String primaryImageUrl = null;
        String rawStructuredData = null;
        String sourceLocator = "document";
        var ingredients = new ArrayList<ParsedIngredient>();
        var steps = new ArrayList<ParsedStep>();

        if (jsonCandidate != null) {
            var recipe = jsonCandidate.recipe();
            title = textValue(recipe.get("name"));
            description = textValue(recipe.get("description"));
            yieldText = textValue(recipe.get("recipeYield"));
            totalTimeText = textValue(recipe.get("totalTime"));
            primaryImageUrl = imageUrl(recipe.get("image"));
            rawStructuredData = jsonCandidate.rawScript();
            sourceLocator = "script[type=application/ld+json][" + jsonCandidate.scriptIndex() + "]";
            ingredients.addAll(readJsonIngredients(recipe.get("recipeIngredient"), sourceLocator));
            steps.addAll(readJsonSteps(recipe.get("recipeInstructions"), sourceLocator));
        }

        if (isBlank(title)) {
            title = firstNonBlank(
                    textOf(document.selectFirst(".view2_summary h3")),
                    attributeOf(document.selectFirst("meta[property=og:title]"), "content")
            );
            domUsed = true;
        }
        if (isBlank(description)) {
            description = firstNonBlank(
                    textOf(document.selectFirst(".view2_summary_in")),
                    attributeOf(document.selectFirst("meta[property=og:description]"), "content")
            );
            domUsed = description != null || domUsed;
        }
        if (isBlank(yieldText)) {
            yieldText = textOf(document.selectFirst(".view2_summary_info1"));
            domUsed = yieldText != null || domUsed;
        }
        if (isBlank(totalTimeText)) {
            totalTimeText = textOf(document.selectFirst(".view2_summary_info2"));
            domUsed = totalTimeText != null || domUsed;
        }
        if (isBlank(primaryImageUrl)) {
            primaryImageUrl = validImageUrl(attributeOf(
                    document.selectFirst("meta[property=og:image]"),
                    "content"
            ));
            domUsed = primaryImageUrl != null || domUsed;
        }
        if (ingredients.isEmpty()) {
            ingredients.addAll(readDomIngredients(document));
            if (!ingredients.isEmpty()) {
                warnings.add("recipeIngredient was unavailable; ingredients came from the recipe DOM");
                domUsed = true;
            }
        }
        if (steps.isEmpty()) {
            steps.addAll(domSteps.stream().map(DomStep::asParsedStep).toList());
            if (!steps.isEmpty()) {
                warnings.add("recipeInstructions was unavailable; steps came from the recipe DOM");
                domUsed = true;
            }
        } else if (!domSteps.isEmpty()) {
            var merged = supplementStepImages(steps, domSteps);
            domUsed |= merged.domUsed();
            steps = new ArrayList<>(merged.steps());
        }

        title = TextNormalizer.normalize(title);
        description = TextNormalizer.normalize(description);
        yieldText = TextNormalizer.normalize(yieldText);
        totalTimeText = TextNormalizer.normalize(totalTimeText);

        if (isBlank(title)) {
            throw parseError("The recipe title was not found");
        }
        if (steps.isEmpty()) {
            throw parseError("No recipe instructions were found");
        }
        if (ingredients.isEmpty()) {
            warnings.add("No ingredient list was found on the source page");
        }

        var servings = parseServings(yieldText);
        if (yieldText != null && servings == null) {
            warnings.add("The serving count could not be represented as one exact number: " + yieldText);
        }

        var sourceFormat = jsonCandidate == null
                ? "DOM"
                : domUsed ? "JSON_LD_WITH_DOM" : "JSON_LD";
        if (jsonCandidate == null) {
            sourceLocator = "DOM:.view2_summary,.ready_ingre3,.view_step_cont";
        }

        return new ParsedRecipe(
                title,
                servings,
                yieldText,
                description,
                totalTimeText,
                primaryImageUrl,
                ingredients,
                steps,
                sourceFormat,
                sourceLocator,
                rawStructuredData,
                warnings,
                readEmbeddedVideo(document)
        );
    }

    // The page loads its player lazily: the real embed URL sits in org_src while src is about:blank.
    private ParsedVideo readEmbeddedVideo(Document document) {
        var iframe = document.selectFirst("iframe#ifrmRecipeVideo");
        if (iframe == null) {
            return null;
        }
        var embedUrl = firstNonBlank(embedUrl(iframe, "org_src"), embedUrl(iframe, "src"));
        if (embedUrl == null) {
            return null;
        }

        var youTubeId = YouTubeUrlPolicy.videoIdFromEmbed(embedUrl);
        if (youTubeId.isPresent()) {
            return new ParsedVideo(
                    ParsedVideo.YOUTUBE,
                    youTubeId.get(),
                    YouTubeUrlPolicy.watchUrl(youTubeId.get()),
                    null,
                    null,
                    true
            );
        }
        var naverTvId = naverTvVideoId(embedUrl);
        if (naverTvId != null) {
            return new ParsedVideo(
                    ParsedVideo.NAVER_TV,
                    naverTvId,
                    "https://tv.naver.com/v/" + naverTvId,
                    null,
                    null,
                    false
            );
        }
        return null;
    }

    private String embedUrl(Element iframe, String attribute) {
        var value = iframe.attr(attribute).trim();
        if (value.isEmpty() || "about:blank".equalsIgnoreCase(value)) {
            return null;
        }
        var absolute = iframe.absUrl(attribute);
        return absolute.isBlank() ? null : absolute;
    }

    private String naverTvVideoId(String embedUrl) {
        final URI uri;
        try {
            uri = new URI(embedUrl);
        } catch (URISyntaxException exception) {
            return null;
        }
        var scheme = uri.getScheme();
        if (scheme == null
                || !("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || !"tv.naver.com".equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getRawPath() == null) {
            return null;
        }
        var matcher = NAVER_TV_EMBED_PATH.matcher(uri.getRawPath());
        return matcher.matches() ? matcher.group(1) : null;
    }

    private JsonRecipeCandidate findJsonLdRecipe(Document document, List<String> warnings) {
        var scripts = document.select("script[type=application/ld+json]");
        for (int index = 0; index < scripts.size(); index++) {
            var raw = scripts.get(index).data();
            if (raw == null || raw.isBlank()) {
                raw = scripts.get(index).html();
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }

            try {
                var root = jsonMapper.readTree(raw);
                var recipe = findRecipeNode(root);
                if (recipe != null) {
                    return new JsonRecipeCandidate(recipe, raw, index);
                }
            } catch (RuntimeException exception) {
                warnings.add("Ignored malformed JSON-LD script at index " + index);
            }
        }
        return null;
    }

    private JsonNode findRecipeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject() && hasType(node.get("@type"), "Recipe")) {
            return node;
        }
        if (node.isArray()) {
            for (var child : node) {
                var found = findRecipeNode(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isObject()) {
            for (var property : node.properties()) {
                var found = findRecipeNode(property.getValue());
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private boolean hasType(JsonNode typeNode, String expected) {
        if (typeNode == null) {
            return false;
        }
        if (typeNode.isTextual()) {
            return expected.equalsIgnoreCase(typeNode.textValue());
        }
        if (typeNode.isArray()) {
            for (var value : typeNode) {
                if (value.isTextual() && expected.equalsIgnoreCase(value.textValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<ParsedIngredient> readJsonIngredients(JsonNode node, String scriptLocator) {
        var rawValues = new ArrayList<String>();
        collectIngredientValues(node, rawValues);
        var ingredients = new ArrayList<ParsedIngredient>();
        for (int index = 0; index < rawValues.size(); index++) {
            var evidence = new SourceEvidence(
                    SourceType.JSON_LD,
                    scriptLocator + ".recipeIngredient[" + index + "]"
            );
            ingredients.add(ingredientTextParser.parse(index + 1, rawValues.get(index), evidence));
        }
        return ingredients;
    }

    private void collectIngredientValues(JsonNode node, List<String> values) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            if (!node.textValue().isBlank()) {
                values.add(node.textValue());
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectIngredientValues(child, values));
            return;
        }
        if (node.isObject()) {
            var name = textValue(node.get("name"));
            var amount = firstNonBlank(
                    textValue(node.get("amount")),
                    textValue(node.get("requiredQuantity"))
            );
            if (name != null) {
                values.add(amount == null ? name : name + " " + amount);
            }
        }
    }

    private List<ParsedStep> readJsonSteps(JsonNode node, String scriptLocator) {
        var values = new ArrayList<RawStep>();
        collectSteps(node, scriptLocator + ".recipeInstructions", values);
        var result = new ArrayList<ParsedStep>();
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            result.add(new ParsedStep(
                    index + 1,
                    value.rawText(),
                    TextNormalizer.normalize(value.rawText()),
                    value.imageUrl(),
                    new SourceEvidence(SourceType.JSON_LD, value.locator())
            ));
        }
        return result;
    }

    private void collectSteps(JsonNode node, String locator, List<RawStep> values) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isTextual()) {
            if (!node.textValue().isBlank()) {
                values.add(new RawStep(node.textValue(), null, locator));
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectSteps(node.get(index), locator + "[" + index + "]", values);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }

        for (var childField : List.of("itemListElement", "steps", "recipeInstructions")) {
            var child = node.get(childField);
            if (child != null && !child.isNull()) {
                collectSteps(child, locator + "." + childField, values);
                return;
            }
        }

        var rawText = firstNonBlank(rawTextValue(node.get("text")), rawTextValue(node.get("name")));
        if (rawText != null) {
            values.add(new RawStep(rawText, imageUrl(node.get("image")), locator));
        }
    }

    private List<ParsedIngredient> readDomIngredients(Document document) {
        var result = new ArrayList<ParsedIngredient>();
        var elements = document.select("#divConfirmedMaterialArea li");
        for (var element : elements) {
            var name = textOf(element.selectFirst(".ingre_list_name"));
            var quantity = textOf(element.selectFirst(".ingre_list_ea"));
            if (name == null) {
                continue;
            }
            var raw = quantity == null ? name : name + " " + quantity;
            var position = result.size() + 1;
            result.add(ingredientTextParser.parse(
                    position,
                    raw,
                    new SourceEvidence(
                            SourceType.DOM,
                            "#divConfirmedMaterialArea li:nth-of-type(" + position + ")"
                    )
            ));
        }
        return result;
    }

    private List<DomStep> readDomSteps(Document document) {
        return document.select("div.view_step_cont").stream()
                .map(this::toDomStep)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(DomStep::number))
                .toList();
    }

    private DomStep toDomStep(Element element) {
        var matcher = DOM_STEP_ID.matcher(element.id());
        if (!matcher.matches()) {
            return null;
        }
        var number = Integer.parseInt(matcher.group(1));
        var description = element.selectFirst("[id=stepdescr" + number + "]");
        if (description == null) {
            description = element.selectFirst(".media-body");
        }
        var rawText = description == null ? null : description.wholeText().trim();
        if (isBlank(rawText)) {
            return null;
        }
        var image = element.selectFirst("[id=stepimg" + number + "] img");
        var imageUrl = image == null ? null : firstNonBlank(
                image.absUrl("src"),
                image.absUrl("data-original"),
                image.attr("src")
        );
        return new DomStep(number, rawText, validImageUrl(imageUrl));
    }

    private StepMergeResult supplementStepImages(List<ParsedStep> jsonSteps, List<DomStep> domSteps) {
        var imagesByNumber = domSteps.stream()
                .filter(step -> step.imageUrl() != null)
                .collect(java.util.stream.Collectors.toMap(
                        DomStep::number,
                        DomStep::imageUrl,
                        (first, ignored) -> first
                ));
        var result = new ArrayList<ParsedStep>();
        var usedDom = false;
        for (var step : jsonSteps) {
            var image = step.imageUrl();
            var evidence = step.evidence();
            if (image == null && imagesByNumber.containsKey(step.number())) {
                image = imagesByNumber.get(step.number());
                evidence = new SourceEvidence(
                        evidence.type(),
                        evidence.locator() + "; image=DOM:#stepimg" + step.number() + " img"
                );
                usedDom = true;
            }
            result.add(new ParsedStep(
                    step.number(),
                    step.rawText(),
                    step.text(),
                    image,
                    evidence
            ));
        }
        return new StepMergeResult(result, usedDom);
    }

    private BigDecimal parseServings(String yieldText) {
        if (yieldText == null) {
            return null;
        }
        var matcher = SERVING_NUMBER.matcher(yieldText);
        BigDecimal value = null;
        int count = 0;
        while (matcher.find()) {
            value = new BigDecimal(matcher.group());
            count++;
        }
        return count == 1 ? value.stripTrailingZeros() : null;
    }

    private String imageUrl(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return validImageUrl(node.textValue());
        }
        if (node.isArray()) {
            for (var value : node) {
                var found = imageUrl(value);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (node.isObject()) {
            for (var field : List.of("url", "contentUrl", "@id")) {
                var found = imageUrl(node.get(field));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String validImageUrl(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            var uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return null;
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String textValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isValueNode()) {
            return TextNormalizer.normalize(node.asText());
        }
        if (node.isArray()) {
            for (var child : node) {
                var value = textValue(child);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private String rawTextValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return node.isValueNode() ? node.asText() : null;
    }

    private String textOf(Element element) {
        return element == null ? null : TextNormalizer.normalize(element.text());
    }

    private String attributeOf(Element element, String attribute) {
        if (element == null || !element.hasAttr(attribute)) {
            return null;
        }
        return TextNormalizer.normalize(element.attr(attribute));
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private RecipeImportException parseError(String message) {
        return new RecipeImportException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "RECIPE_PARSE_ERROR",
                message
        );
    }

    private record JsonRecipeCandidate(JsonNode recipe, String rawScript, int scriptIndex) {
    }

    private record RawStep(String rawText, String imageUrl, String locator) {
    }

    private record StepMergeResult(List<ParsedStep> steps, boolean domUsed) {
    }

    private record DomStep(int number, String rawText, String imageUrl) {
        ParsedStep asParsedStep() {
            return new ParsedStep(
                    number,
                    rawText,
                    TextNormalizer.normalize(rawText),
                    imageUrl,
                    new SourceEvidence(SourceType.DOM, "#stepDiv" + number)
            );
        }
    }
}
