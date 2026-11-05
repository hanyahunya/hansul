package com.hansul.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.domain.ParsedStep;
import com.hansul.recipe.domain.ParsedVideo;
import com.hansul.recipe.domain.SourceEvidence;
import com.hansul.recipe.domain.SourceEvidence.SourceType;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotEntity;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecipeResponseMapperTest {

    private final RecipeResponseMapper mapper = new RecipeResponseMapper();

    @Test
    void mapsAVideoRecipeWithItsVideoAndTheSegmentOfEveryStep() {
        var entity = RecipeSnapshotEntity.create(
                YouTubeUrlPolicy.forVideoId(VideoRecipeFixtures.VIDEO_ID),
                VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요."),
                "youtube-gemini-v1",
                "hash",
                Instant.EPOCH
        );

        var response = mapper.toResponse(entity);

        assertThat(response.sourceUrl()).isEqualTo(VideoRecipeFixtures.WATCH_URL);
        assertThat(response.sourceKind()).isEqualTo("VIDEO_AI");
        assertThat(response.parserVersion()).isEqualTo("youtube-gemini-v1");
        assertThat(response.primaryImageUrl()).isEqualTo("https://i.ytimg.com/vi/AbCdEfGhIjK/hqdefault.jpg");
        assertThat(response.video()).isEqualTo(new RecipeImportResponse.Video(
                "YOUTUBE", "AbCdEfGhIjK", VideoRecipeFixtures.WATCH_URL, 754, "채널명", true));
        assertThat(response.steps()).hasSize(2);
        assertThat(response.steps().getFirst()).isEqualTo(new RecipeImportResponse.Step(
                1,
                "양파 1개를 채 썰어주세요.",
                null,
                "양파 한 개 채 썰어 줄게요",
                new RecipeImportResponse.Evidence("VIDEO_SEGMENT", "segments:s3-s4@83-112"),
                83,
                112,
                95
        ));
        assertThat(response.ingredients()).extracting(ingredient -> ingredient.evidence().sourceType())
                .containsExactly("VIDEO_DESCRIPTION", "VIDEO_SEGMENT");
        assertThat(response.warnings()).containsExactly(VideoRecipeFixtures.AI_WARNING);
    }

    @Test
    void mapsAnUploadToItsPseudoSourceUrlAndAnUploadVideo() {
        var uploadId = UUID.fromString("5f0c7d1e-8a2b-4c3d-9e4f-0a1b2c3d4e5f");
        var entity = RecipeSnapshotEntity.create(
                CanonicalRecipeUrl.forUpload(uploadId),
                VideoRecipeFixtures.uploadRecipe(uploadId.toString(), 612),
                "upload-gemini-v1",
                "hash",
                Instant.EPOCH,
                "owner-subject"
        );

        var response = mapper.toResponse(entity);

        assertThat(response.sourceUrl()).isEqualTo("hansul-upload:5f0c7d1e-8a2b-4c3d-9e4f-0a1b2c3d4e5f");
        assertThat(response.sourceKind()).isEqualTo("VIDEO_AI");
        assertThat(response.primaryImageUrl()).isNull();
        assertThat(response.video()).isEqualTo(new RecipeImportResponse.Video(
                "UPLOAD", null, null, 612, null, false));
    }

    @Test
    void mapsAWebPageRecipeWithoutAVideoExactlyAsBefore() {
        var response = mapper.toResponse(webPageSnapshot(null));

        assertThat(response.sourceKind()).isEqualTo("WEB_PAGE");
        assertThat(response.video()).isNull();
        assertThat(response.steps()).singleElement().satisfies(step -> {
            assertThat(step.imageUrl()).isEqualTo("https://recipe1.ezmember.co.kr/one.jpg");
            assertThat(step.videoStartSeconds()).isNull();
            assertThat(step.videoEndSeconds()).isNull();
            assertThat(step.keyFrameSeconds()).isNull();
        });
    }

    @Test
    void mapsAnEmbedded10000recipeVideoWithoutTurningTheRecipeIntoAnAiRecipe() {
        var naverTv = new ParsedVideo(
                ParsedVideo.NAVER_TV, "52277225", "https://tv.naver.com/v/52277225", null, null, false);

        var response = mapper.toResponse(webPageSnapshot(naverTv));

        assertThat(response.sourceKind()).isEqualTo("WEB_PAGE");
        assertThat(response.video()).isEqualTo(new RecipeImportResponse.Video(
                "NAVER_TV", "52277225", "https://tv.naver.com/v/52277225", null, null, false));
    }

    private RecipeSnapshotEntity webPageSnapshot(ParsedVideo video) {
        var parsed = new ParsedRecipe(
                "DOM 레시피",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(new ParsedStep(1, "양파를 썹니다.", "양파를 썹니다.",
                        "https://recipe1.ezmember.co.kr/one.jpg",
                        new SourceEvidence(SourceType.DOM, "#stepDiv1"))),
                "DOM",
                "DOM:.view2_summary,.ready_ingre3,.view_step_cont",
                null,
                List.of(),
                video
        );
        return RecipeSnapshotEntity.create(
                new Hundred00RecipeUrlPolicy().canonicalize("https://www.10000recipe.com/recipe/123"),
                parsed,
                "10000recipe-v2",
                "hash",
                Instant.EPOCH
        );
    }
}
