package com.hansul.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.domain.ParsedStep;
import com.hansul.recipe.domain.ParsedVideo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class RecipeFingerprintTest {

    private final RecipeFingerprint fingerprint = new RecipeFingerprint();
    private final ParsedRecipe base = VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요.");
    private final String baseHash = fingerprint.calculate(base, "youtube-gemini-v1");

    @Test
    void isStableForIdenticalContent() {
        assertThat(fingerprint.calculate(VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요."),
                "youtube-gemini-v1")).isEqualTo(baseHash);
    }

    @Test
    void changesWhenTheVideoIdentityOrDurationChanges() {
        var video = base.video();
        assertThat(hashWithVideo(null)).isNotEqualTo(baseHash);
        assertThat(hashWithVideo(new ParsedVideo(ParsedVideo.UPLOAD, video.videoId(), video.pageUrl(),
                video.durationSeconds(), video.channel(), video.embeddable()))).isNotEqualTo(baseHash);
        assertThat(hashWithVideo(new ParsedVideo(video.provider(), "ZyXwVuTsRqP", video.pageUrl(),
                video.durationSeconds(), video.channel(), video.embeddable()))).isNotEqualTo(baseHash);
        assertThat(hashWithVideo(new ParsedVideo(video.provider(), video.videoId(), "https://youtu.be/AbCdEfGhIjK",
                video.durationSeconds(), video.channel(), video.embeddable()))).isNotEqualTo(baseHash);
        assertThat(hashWithVideo(new ParsedVideo(video.provider(), video.videoId(), video.pageUrl(),
                755, video.channel(), video.embeddable()))).isNotEqualTo(baseHash);
    }

    @Test
    void changesWhenAStepMovesToAnotherPartOfTheVideo() {
        assertThat(hashWithFirstStep(step -> withTimes(step, 84, step.videoEndSeconds(), step.keyFrameSeconds())))
                .isNotEqualTo(baseHash);
        assertThat(hashWithFirstStep(step -> withTimes(step, step.videoStartSeconds(), 113, step.keyFrameSeconds())))
                .isNotEqualTo(baseHash);
        assertThat(hashWithFirstStep(step -> withTimes(step, step.videoStartSeconds(), step.videoEndSeconds(), null)))
                .isNotEqualTo(baseHash);
    }

    private String hashWithVideo(ParsedVideo video) {
        return fingerprint.calculate(copy(base.steps(), video), "youtube-gemini-v1");
    }

    private String hashWithFirstStep(UnaryOperator<ParsedStep> change) {
        var steps = new ArrayList<>(base.steps());
        steps.set(0, change.apply(steps.getFirst()));
        return fingerprint.calculate(copy(steps, base.video()), "youtube-gemini-v1");
    }

    private ParsedStep withTimes(ParsedStep step, Integer start, Integer end, Integer keyFrame) {
        return new ParsedStep(step.number(), step.rawText(), step.text(), step.imageUrl(), step.evidence(),
                start, end, keyFrame);
    }

    private ParsedRecipe copy(List<ParsedStep> steps, ParsedVideo video) {
        return new ParsedRecipe(base.title(), base.baseServings(), base.baseServingsText(), base.description(),
                base.totalTimeText(), base.primaryImageUrl(), base.ingredients(), steps, base.sourceFormat(),
                base.sourceEvidenceLocator(), base.rawStructuredData(), base.warnings(), video);
    }
}
