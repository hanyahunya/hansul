package com.hansul.cooking;

import java.util.Set;
import java.util.regex.Pattern;

/** Conservative linguistic checks, not a semantic proof of a physical cooking action. */
final class UserEvidencePolicy {
    enum Purpose { ACTION_DONE, ACTION_RETRACTED, CONDITION_OBSERVED, CONDITION_RETRACTED,
        MISSING, AVAILABLE, ADDED, REPLACED }

    private static final Set<String> BARE_CONFIRMATIONS = Set.of(
            "응", "네", "예", "어", "맞아", "맞아요", "그래", "그래요", "ㅇㅇ");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("[,.!?？。;:，；\\n]");
    private static final Pattern QUESTION_OR_HYPOTHESIS = Pattern.compile(
            "[?？]|만약|가정|예정|려고|(?:라고|다고|다는|라던)|"
                    + "(?:했|었|았|있|없|넣|썰|끓|하|되|바꾸|쓰|자르|켜)(?:으?면|다면)|"
                    + "할까|될까|을까|써도|해도|켜도|어떻게|어때|맞아|야\\s*(?:돼|되|하)|"
                    + "(?:인가|했나|됐나|없나|있나|까요|습니까)(?:요)?\\s*$");
    private static final Pattern NEGATIVE_ACTION = Pattern.compile(
            "(?<![가-힣])(?:안|못)\\s+|"
                    + "(?:안|못)(?:했|해|하|넣|썰|끓|켜|시작|맞|완료|바꿨|썼|사용|할|된|됐)|"
                    + "않|못하|아니|아냐|아직|미완료|하지\\s*마|말아");
    private static final Pattern DENIAL_OR_CORRECTION = Pattern.compile("아니|아냐|정정|취소|없지\\s*않|안\\s*없");
    private static final Pattern SHORTAGE_DENIAL = Pattern.compile(
            "아니|아냐|않|(?:안|못)\\s*(?:없|했|해|하)|하지\\s*마|말아");
    private static final Pattern ABSENCE = Pattern.compile("없|부족|모자라|떨어졌|다\\s*썼");
    private static final Pattern AVAILABILITY = Pattern.compile("있|사왔|구했|찾았|준비했");
    private static final Pattern COMPLETED_PREDICATE = Pattern.compile(
            "았|었|였|했|됐|렸|켰|껐|뒀|놨|썼|꿨|쳤|냈|완료|끝났|끝냈|마쳤");
    private static final Pattern CORRECTION_CLAUSE = Pattern.compile("^\\s*(?:아니|아냐|정정|취소)");
    private static final Pattern TIMER_COMMAND = Pattern.compile(
            "(?<![가-힣])(?:시작해|켜줘|맞춰줘|설정해|해줘|맞춰|시작해줘|설정해줘)");

    private UserEvidencePolicy() {}

    static boolean containsExactSpan(String transcript, String evidence) {
        return findSpan(transcript, evidence, null);
    }

    static boolean permitsReport(String transcript, String evidence, Purpose purpose) {
        String bare = evidence.strip().replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
        return !BARE_CONFIRMATIONS.contains(bare) && findSpan(transcript, evidence, purpose);
    }

    private static boolean findSpan(String transcript, String evidence, Purpose purpose) {
        int from = 0;
        while (from < transcript.length()) {
            int start = transcript.indexOf(evidence, from);
            if (start < 0) {
                return false;
            }
            int end = start + evidence.length();
            boolean wordStart = start == 0 || !Character.isLetterOrDigit(transcript.charAt(start - 1));
            boolean wordEnd = end == transcript.length() || !Character.isLetterOrDigit(transcript.charAt(end));
            if (wordStart && wordEnd && (purpose == null || permitsClause(transcript, start, end, purpose))) {
                return true;
            }
            from = start + 1;
        }
        return false;
    }

    private static boolean permitsClause(String transcript, int start, int end, Purpose purpose) {
        int left = start;
        while (left > 0 && !isBoundary(transcript.charAt(left - 1))) {
            left--;
        }
        int right = end;
        while (right < transcript.length() && !isBoundary(transcript.charAt(right))) {
            right++;
        }
        String clause = transcript.substring(left, right).strip();
        if (QUESTION_OR_HYPOTHESIS.matcher(clause).find()
                || (right < transcript.length() && (transcript.charAt(right) == '?' || transcript.charAt(right) == '？'))
                || (end > start && QUESTION_OR_HYPOTHESIS.matcher(transcript.substring(start, end)).find())) {
            return false;
        }
        // A following explicit correction invalidates a preceding positive fragment. Do not guess
        // which part of a contrast such as "안 했지만 지금 했어" describes the current state.
        String following = transcript.substring(right).replaceFirst("^[,.!?？。;:，；\\s]+", "");
        if (CORRECTION_CLAUSE.matcher(following).find()) {
            return false;
        }
        return switch (purpose) {
            case ACTION_DONE, ADDED, REPLACED -> !NEGATIVE_ACTION.matcher(clause).find()
                    && COMPLETED_PREDICATE.matcher(clause).find();
            case CONDITION_OBSERVED -> !NEGATIVE_ACTION.matcher(clause).find();
            case ACTION_RETRACTED, CONDITION_RETRACTED ->
                    NEGATIVE_ACTION.matcher(clause).find() || DENIAL_OR_CORRECTION.matcher(clause).find();
            case MISSING -> ABSENCE.matcher(clause).find()
                    && !AVAILABILITY.matcher(clause).find()
                    && !DENIAL_OR_CORRECTION.matcher(clause).find()
                    && !SHORTAGE_DENIAL.matcher(clause).find();
            case AVAILABLE -> AVAILABILITY.matcher(clause).find()
                    && !ABSENCE.matcher(clause).find() && !NEGATIVE_ACTION.matcher(clause).find();
        };
    }

    static boolean permitsTimerConsent(String transcript) {
        String text = transcript.strip().replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
        return !QUESTION_OR_HYPOTHESIS.matcher(transcript).find()
                && !NEGATIVE_ACTION.matcher(transcript).find()
                && (BARE_CONFIRMATIONS.contains(text) || TIMER_COMMAND.matcher(text).find());
    }

    private static boolean isBoundary(char character) {
        return CLAUSE_BOUNDARY.matcher(Character.toString(character)).matches();
    }
}
