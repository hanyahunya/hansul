package com.hansul.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hansul.auth.api.AuthenticatedUser;
import com.hansul.auth.api.AuthenticatedUserId;
import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.recipe.infrastructure.parser.Hundred00RecipeDocumentParser;
import com.hansul.recipe.infrastructure.parser.IngredientTextParser;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotEntity;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import com.hansul.recipe.infrastructure.source.RecipeSourcePolicy;
import com.hansul.recipe.infrastructure.source.UnsupportedVideoPlatformException;
import com.hansul.recipe.infrastructure.source.YouTubeUrlPolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

class RecipeImportServiceTest {

    private static final String PAGE_WITH_VIDEO = """
            <html><body>
              <div class="view2_summary"><h3>영상이 있는 레시피</h3></div>
              <iframe id="ifrmRecipeVideo" src="about:blank"
                      org_src="https://www.youtube.com/embed/AbCdEfGhIjK"></iframe>
              <div id="stepDiv1" class="view_step_cont"><div id="stepdescr1">양파를 썹니다.</div></div>
            </body></html>
            """;

    private final RecipeSnapshotRepository repository = mock(RecipeSnapshotRepository.class);
    private final List<CanonicalRecipeUrl> fetchedPages = new ArrayList<>();
    private final AtomicReference<String> signedInSubject = new AtomicReference<>();
    private final CurrentUserProvider currentUser = () -> Optional.ofNullable(signedInSubject.get())
            .map(subject -> new AuthenticatedUser(new AuthenticatedUserId(subject), null, null));
    private final RecipeImportService service = new RecipeImportService(
            new RecipeSourcePolicy(new Hundred00RecipeUrlPolicy(), new YouTubeUrlPolicy()),
            source -> {
                fetchedPages.add(source);
                return PAGE_WITH_VIDEO;
            },
            new Hundred00RecipeDocumentParser(JsonMapper.builder().build(), new IngredientTextParser()),
            repository,
            new RecipeResponseMapper(),
            new RecipeFingerprint(),
            currentUser,
            Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void sendsYouTubeLinksOnTheSynchronousEndpointToImportJobs() {
        assertThatThrownBy(() -> service.importRecipe("https://youtu.be/AbCdEfGhIjK"))
                .isInstanceOfSatisfying(RecipeImportException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VIDEO_IMPORT_REQUIRES_JOB");
                });
        assertThat(fetchedPages).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnsupportedVideoPlatformsBeforeFetchingAnything() {
        assertThatThrownBy(() -> service.importRecipe("https://www.instagram.com/reel/C1a2b3c4d5e/"))
                .isInstanceOf(UnsupportedVideoPlatformException.class);
        assertThat(fetchedPages).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void imports10000recipePagesAsPublicSnapshotsThatShowTheirEmbeddedVideo() {
        when(repository.findBySourceProviderAndSourceRecipeIdAndContentHash(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.importRecipe("https://m.10000recipe.com/recipe/6944989");

        assertThat(fetchedPages).extracting(source -> source.uri().toString())
                .containsExactly("https://www.10000recipe.com/recipe/6944989");
        var saved = ArgumentCaptor.forClass(RecipeSnapshotEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOwnerSubject()).isNull();
        assertThat(response.parserVersion()).isEqualTo("10000recipe-v2");
        assertThat(response.sourceKind()).isEqualTo("WEB_PAGE");
        assertThat(response.video()).isNotNull();
        assertThat(response.video().provider()).isEqualTo("YOUTUBE");
        assertThat(response.video().pageUrl()).isEqualTo("https://www.youtube.com/watch?v=AbCdEfGhIjK");
        assertThat(response.steps()).singleElement()
                .satisfies(step -> assertThat(step.videoStartSeconds()).isNull());
    }

    @Test
    void showsPublicSnapshotsToEveryone() {
        var snapshot = videoSnapshot(null);
        when(repository.findById(snapshot.getId())).thenReturn(Optional.of(snapshot));

        assertThat(service.getRecipe(snapshot.getId()).id()).isEqualTo(snapshot.getId());
        signedInSubject.set("someone");
        assertThat(service.getRecipe(snapshot.getId()).id()).isEqualTo(snapshot.getId());
    }

    @Test
    void hidesOwnedSnapshotsFromEveryoneButTheirOwner() {
        var snapshot = videoSnapshot("owner-subject");
        when(repository.findById(snapshot.getId())).thenReturn(Optional.of(snapshot));

        signedInSubject.set("owner-subject");
        assertThat(service.getRecipe(snapshot.getId()).sourceKind()).isEqualTo("VIDEO_AI");

        signedInSubject.set("other-subject");
        assertThatThrownBy(() -> service.getRecipe(snapshot.getId()))
                .isInstanceOf(RecipeNotFoundException.class);

        signedInSubject.set(null);
        assertThatThrownBy(() -> service.getRecipe(snapshot.getId()))
                .isInstanceOf(RecipeNotFoundException.class);
    }

    @Test
    void savesVideoRecipesWithTheirOwnerAndReusesAnIdenticalSnapshot() {
        var uploadId = UUID.randomUUID();
        var source = CanonicalRecipeUrl.forUpload(uploadId);
        var parsed = VideoRecipeFixtures.uploadRecipe(uploadId.toString(), 612);
        when(repository.findBySourceProviderAndSourceRecipeIdAndContentHash(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var id = service.saveVideoRecipe(source, parsed, "upload-gemini-v1", "owner-subject");

        var saved = ArgumentCaptor.forClass(RecipeSnapshotEntity.class);
        verify(repository).save(saved.capture());
        var entity = saved.getValue();
        assertThat(entity.getId()).isEqualTo(id);
        assertThat(entity.getOwnerSubject()).isEqualTo("owner-subject");
        assertThat(entity.getSourceProvider()).isEqualTo("upload");
        assertThat(entity.getSourceRecipeId()).isEqualTo(uploadId.toString());
        assertThat(entity.getSourceUrl()).isEqualTo("hansul-upload:" + uploadId);
        assertThat(entity.getParserVersion()).isEqualTo("upload-gemini-v1");
        assertThat(entity.getVideoProvider()).isEqualTo("UPLOAD");
        assertThat(entity.getVideoDurationSeconds()).isEqualTo(612);
        assertThat(entity.getImportedAt()).isEqualTo(Instant.parse("2026-11-05T00:00:00Z"));

        when(repository.findBySourceProviderAndSourceRecipeIdAndContentHash(any(), any(), any()))
                .thenReturn(Optional.of(entity));
        assertThat(service.saveVideoRecipe(source, parsed, "upload-gemini-v1", "owner-subject")).isEqualTo(id);
        verify(repository).save(any());
    }

    @Test
    void neverHandsOutAnIdenticalSnapshotThatBelongsToSomeoneElse() {
        var owned = videoSnapshot("owner-subject");
        when(repository.findBySourceProviderAndSourceRecipeIdAndContentHash(any(), any(), any()))
                .thenReturn(Optional.of(owned));

        assertThatThrownBy(() -> service.saveVideoRecipe(
                YouTubeUrlPolicy.forVideoId(VideoRecipeFixtures.VIDEO_ID),
                VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요."),
                "youtube-gemini-v1",
                null
        )).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void findsTheNewestPublicVideoSnapshot() {
        var snapshot = videoSnapshot(null);
        when(repository
                .findFirstBySourceProviderAndSourceRecipeIdAndParserVersionAndOwnerSubjectIsNullOrderByImportedAtDesc(
                        "youtube", VideoRecipeFixtures.VIDEO_ID, "youtube-gemini-v1"))
                .thenReturn(Optional.of(snapshot));

        assertThat(service.findCachedVideoRecipe("youtube", VideoRecipeFixtures.VIDEO_ID, "youtube-gemini-v1"))
                .contains(snapshot.getId());
        assertThat(service.findCachedVideoRecipe("youtube", "ZyXwVuTsRqP", "youtube-gemini-v1")).isEmpty();
    }

    private RecipeSnapshotEntity videoSnapshot(String ownerSubject) {
        return RecipeSnapshotEntity.create(
                YouTubeUrlPolicy.forVideoId(VideoRecipeFixtures.VIDEO_ID),
                VideoRecipeFixtures.youTubeRecipe("양파 1개를 채 썰어주세요."),
                "youtube-gemini-v1",
                "hash",
                Instant.EPOCH,
                ownerSubject
        );
    }
}
