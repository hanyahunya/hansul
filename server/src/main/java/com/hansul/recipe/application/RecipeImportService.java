package com.hansul.recipe.application;

import com.hansul.auth.api.CurrentUserProvider;
import com.hansul.recipe.api.RecipeImportResponse;
import com.hansul.recipe.domain.ParsedRecipe;
import com.hansul.recipe.infrastructure.client.RecipePageClient;
import com.hansul.recipe.infrastructure.parser.Hundred00RecipeDocumentParser;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotEntity;
import com.hansul.recipe.infrastructure.persistence.RecipeSnapshotRepository;
import com.hansul.recipe.infrastructure.source.CanonicalRecipeUrl;
import com.hansul.recipe.infrastructure.source.Hundred00RecipeUrlPolicy;
import com.hansul.recipe.infrastructure.source.RecipeSourcePolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipeImportService {

    private final RecipeSourcePolicy sourcePolicy;
    private final RecipePageClient pageClient;
    private final Hundred00RecipeDocumentParser parser;
    private final RecipeSnapshotRepository repository;
    private final RecipeResponseMapper responseMapper;
    private final RecipeFingerprint fingerprint;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;

    public RecipeImportService(
            RecipeSourcePolicy sourcePolicy,
            RecipePageClient pageClient,
            Hundred00RecipeDocumentParser parser,
            RecipeSnapshotRepository repository,
            RecipeResponseMapper responseMapper,
            RecipeFingerprint fingerprint,
            CurrentUserProvider currentUserProvider,
            Clock clock
    ) {
        this.sourcePolicy = sourcePolicy;
        this.pageClient = pageClient;
        this.parser = parser;
        this.repository = repository;
        this.responseMapper = responseMapper;
        this.fingerprint = fingerprint;
        this.currentUserProvider = currentUserProvider;
        this.clock = clock;
    }

    @Transactional
    public RecipeImportResponse importRecipe(String rawUrl) {
        var source = sourcePolicy.canonicalize(rawUrl);
        if (!Hundred00RecipeUrlPolicy.PROVIDER.equals(source.provider())) {
            throw new RecipeImportException(
                    HttpStatus.BAD_REQUEST,
                    "VIDEO_IMPORT_REQUIRES_JOB",
                    "Video recipes are imported through POST /v1/recipe-import-jobs"
            );
        }

        var html = pageClient.fetch(source);
        var parsed = parser.parse(html, source);
        var contentHash = fingerprint.calculate(
                parsed,
                Hundred00RecipeDocumentParser.PARSER_VERSION
        );

        var existing = repository.findBySourceProviderAndSourceRecipeIdAndContentHash(
                source.provider(),
                source.recipeId(),
                contentHash
        );
        if (existing.isPresent()) {
            return responseMapper.toResponse(existing.get());
        }

        var entity = RecipeSnapshotEntity.create(
                source,
                parsed,
                Hundred00RecipeDocumentParser.PARSER_VERSION,
                contentHash,
                Instant.now(clock)
        );
        return responseMapper.toResponse(repository.save(entity));
    }

    /** Owned snapshots (uploaded videos) look like missing recipes to everyone but their owner. */
    @Transactional(readOnly = true)
    public RecipeImportResponse getRecipe(UUID id) {
        return repository.findById(id)
                .filter(this::isVisibleToCurrentUser)
                .map(responseMapper::toResponse)
                .orElseThrow(() -> new RecipeNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findCachedVideoRecipe(String provider, String sourceRecipeId, String parserVersion) {
        return repository
                .findFirstBySourceProviderAndSourceRecipeIdAndParserVersionAndOwnerSubjectIsNullOrderByImportedAtDesc(
                        provider,
                        sourceRecipeId,
                        parserVersion
                )
                .map(RecipeSnapshotEntity::getId);
    }

    /** {@code ownerSubject} null stores a public snapshot (shared YouTube cache). */
    @Transactional
    public UUID saveVideoRecipe(
            CanonicalRecipeUrl source,
            ParsedRecipe parsed,
            String parserVersion,
            String ownerSubject
    ) {
        var contentHash = fingerprint.calculate(parsed, parserVersion);
        var existing = repository.findBySourceProviderAndSourceRecipeIdAndContentHash(
                source.provider(),
                source.recipeId(),
                contentHash
        );
        if (existing.isPresent()) {
            // The source decides the owner (public YouTube cache, one uploader per upload), so a match
            // owned by someone else is a caller bug; handing out its id would leak or hide the recipe.
            if (!Objects.equals(existing.get().getOwnerSubject(), ownerSubject)) {
                throw new IllegalStateException("An identical recipe snapshot belongs to a different owner");
            }
            return existing.get().getId();
        }

        var entity = RecipeSnapshotEntity.create(
                source,
                parsed,
                parserVersion,
                contentHash,
                Instant.now(clock),
                ownerSubject
        );
        return repository.save(entity).getId();
    }

    private boolean isVisibleToCurrentUser(RecipeSnapshotEntity snapshot) {
        var owner = snapshot.getOwnerSubject();
        return owner == null || currentUserProvider.findCurrentUser()
                .map(user -> owner.equals(user.id().value()))
                .orElse(false);
    }
}
