package com.hansul.cooking;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A model proposes this typed decision; the server validates it before any state change. */
public record TurnDecision(
        Addressing addressing,
        SpeechAct speechAct,
        String replyText,
        Set<Integer> citedSourceSteps,
        Set<Integer> photoSourceSteps,
        List<UserReport> reports,
        List<String> timerOfferIds,
        List<String> timerAcceptanceIds,
        List<IngredientAdjustment> ingredientReports) {

    public TurnDecision {
        Objects.requireNonNull(addressing, "addressing");
        Objects.requireNonNull(speechAct, "speechAct");
        replyText = Objects.requireNonNullElse(replyText, "");
        citedSourceSteps = Set.copyOf(Objects.requireNonNull(citedSourceSteps, "citedSourceSteps"));
        photoSourceSteps = Set.copyOf(Objects.requireNonNull(photoSourceSteps, "photoSourceSteps"));
        reports = List.copyOf(Objects.requireNonNull(reports, "reports"));
        timerOfferIds = List.copyOf(Objects.requireNonNull(timerOfferIds, "timerOfferIds"));
        timerAcceptanceIds = List.copyOf(Objects.requireNonNull(timerAcceptanceIds, "timerAcceptanceIds"));
        ingredientReports = ingredientReports == null ? List.of() : List.copyOf(ingredientReports);
        if (ingredientReports.size() > 20) {
            throw new IllegalArgumentException("Too many ingredient reports in one turn");
        }
    }

    public TurnDecision(Addressing addressing, SpeechAct speechAct, String replyText,
            Set<Integer> citedSourceSteps, Set<Integer> photoSourceSteps, List<UserReport> reports,
            List<String> timerOfferIds, List<String> timerAcceptanceIds) {
        this(addressing, speechAct, replyText, citedSourceSteps, photoSourceSteps, reports,
                timerOfferIds, timerAcceptanceIds, List.of());
    }

    public enum Addressing {
        DIRECT,
        UNRELATED,
        UNCERTAIN
    }

    public enum SpeechAct {
        QUESTION_ONLY,
        REPORT_OR_COMMAND,
        MIXED,
        OTHER
    }

    public enum ReportKind {
        ACTION_DONE,
        ACTION_RETRACTED,
        CONDITION_OBSERVED,
        CONDITION_RETRACTED
    }

    /** evidence must be an exact span of the latest user transcript. */
    public record UserReport(ReportKind kind, String targetId, String evidence) {
        public UserReport {
            Objects.requireNonNull(kind, "kind");
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalArgumentException("Report target must be nonempty");
            }
            if (evidence == null || evidence.isBlank()) {
                throw new IllegalArgumentException("Report evidence must be nonempty");
            }
        }
    }
}
