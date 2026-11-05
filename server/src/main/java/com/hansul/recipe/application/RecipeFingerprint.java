package com.hansul.recipe.application;

import com.hansul.recipe.domain.ParsedRecipe;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
class RecipeFingerprint {

    String calculate(ParsedRecipe recipe, String parserVersion) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            add(digest, parserVersion);
            add(digest, recipe.title());
            add(digest, recipe.baseServings() == null ? null : recipe.baseServings().toPlainString());
            add(digest, recipe.baseServingsText());
            add(digest, recipe.description());
            add(digest, recipe.totalTimeText());
            add(digest, recipe.primaryImageUrl());
            for (var ingredient : recipe.ingredients()) {
                add(digest, ingredient.rawText());
                add(digest, ingredient.name());
                add(digest, ingredient.quantityText());
            }
            for (var step : recipe.steps()) {
                add(digest, step.rawText());
                add(digest, step.text());
                add(digest, step.imageUrl());
                add(digest, step.videoStartSeconds());
                add(digest, step.videoEndSeconds());
                add(digest, step.keyFrameSeconds());
            }
            var video = recipe.video();
            add(digest, video == null ? null : video.provider());
            add(digest, video == null ? null : video.videoId());
            add(digest, video == null ? null : video.pageUrl());
            add(digest, video == null ? null : video.durationSeconds());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private void add(MessageDigest digest, Integer value) {
        add(digest, value == null ? null : value.toString());
    }

    private void add(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
