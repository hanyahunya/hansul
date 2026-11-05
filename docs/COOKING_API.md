# Cooking session API and evidence rules

Updated 2026-10-12. These endpoints use the existing authenticated session owner, optimistic state version checks, and idempotent client command IDs. Android and any local model should apply the same state/evidence rules.

## Viewing and progress are separate

`POST /v1/cooking-sessions/{id}/navigation`

```json
{"clientNavigationId":"unique-per-navigation","sourceStepNumber":4}
```

The response contains `sessionId`, `clientNavigationId`, `sourceStepNumber`, `photos`, `events`, `state`, `availableActionIds`, and `duplicate`. `photos` contains only the imported photo for that source step, if one exists. Any source step may be viewed in any order. Viewing changes only `state.focusedSourceStepNumber`; it never completes earlier steps, observes boiling/doneness, or starts a timer. A photo shown in an AI answer also does not move this viewing focus.

`POST /v1/cooking-sessions/{id}/step-progress`

```json
{"clientCommandId":"unique-per-button-action","sourceStepNumber":4,"status":"SKIPPED"}
```

`status` is `COMPLETED`, `SKIPPED`, or `PENDING`. The response contains `sessionId`, `clientCommandId`, `sourceStepNumber`, `status`, `events`, `state`, `availableActionIds`, and `duplicate`. Completion and skipping never move the focus. Completion marks only actions whose referenced source steps have all been explicitly completed. A multi-step action is not completed by marking only one of its source steps. Skipping is recorded in `skippedSourceStepNumbers`, never in `completedActionIds`, and does not satisfy safety/physical conditions or prerequisites. `PENDING` reverses the source-step mark and retracts related action completion; already observed physical conditions and running timers require their own correction.

The additional state fields are:

- `focusedSourceStepNumber`: nullable viewing position.
- `completedSourceStepNumbers`: explicit source-step completion, also synchronized when all source-linked actions have been reported done.
- `skippedSourceStepNumbers`: explicit skip choices, disjoint from completed source steps.
- `ingredientAdjustments`: latest user-reported fact per ingredient name.

Older state JSON without these fields loads with `null` focus and empty collections. No schema migration is required because the projection remains in the existing state JSON column. Clients should serialize navigation/progress/turn commands to avoid stale state conflicts. Retrying the same command ID and payload returns the saved result; reusing that ID for a different payload returns HTTP 409. Invalid source step numbers return HTTP 400.

Model inference runs outside database transactions and does not hold a session lock, so touch navigation/progress/timer commands can commit while inference is pending. Transition writes use an optimistic `state_version` comparison inside a short transaction. If a manual command changes the session before an old model proposal commits, the turn returns HTTP 409 without applying that stale proposal or saving it as completed. There is no automatic rebase or hidden second model call. The client can explicitly retry the same uncommitted turn ID with the latest state; it must preserve successful touch commands and show that a retry is needed.

## Manual source timers

`POST /v1/cooking-sessions/{id}/timer-start-requests`

```json
{"clientCommandId":"unique-per-timer-tap","timerCueId":"source-timer-step-4"}
```

The response contains `sessionId`, `clientCommandId`, `timerCueId`, `events`, `state`, `availableActionIds`, and `duplicate`. Explicit consent requests an existing plan cue even if the model never offered it. An existing offer is cleared. This changes the cue to requested, not running; the device schedules it and calls the existing `timer-start-acks` endpoint before the server records a deadline.

The source-order fallback now extracts a conservative timer cue when a step contains one explicit duration, including approximate durations. It omits ranges, bounds, separate time expressions, and whole-recipe expected times. It does not invent a duration or auto-start a cue. No model call is necessary to create a fallback cue or request a manual timer.

Unknown cues return HTTP 400. A different command for a requested/running/already-started cue returns HTTP 409, preventing accidental re-scheduling and duplicate timer-deadline inserts. The same command/payload retries the saved response with `duplicate=true`; it must not schedule another device timer. A new run of the same source cue after expiry is not supported by the current one-deadline-per-cue contract.

## Ingredient changes and substitutions

The model receives the imported ingredient names and quantities, source steps, base and target servings, viewing focus, explicit progress, and prior ingredient adjustments. It proposes `ingredientReports` alongside its existing decision:

```json
{
  "kind":"REPLACED",
  "ingredientName":"양파",
  "replacementName":"대파",
  "quantityText":"",
  "evidence":"양파 대신 대파 넣었어"
}
```

Kinds are `MISSING`, `AVAILABLE`, `ADDED`, and `REPLACED`. Replacement name is nonempty only for `REPLACED`. A quantity is stored only if explicitly spoken. Every name and quantity must appear in an exact, complete evidence span from the latest user transcript. Questions, unrelated/uncertain speech, and AI suggestions cannot mutate ingredient facts. Reports have field/count limits, and newer reports replace the previous fact for the same ingredient name. A replacement report does not automatically complete any recipe action.

`ADDED` and `REPLACED` mean reported actual use, not a future plan or a decision to use something. The engine checks the evidence's surrounding sentence, rather than accepting a positive fragment inside a negative/question/hypothetical sentence. “버터 없어” can assert `MISSING`; “버터 안 넣었어” cannot assert `ADDED`. Ambiguous contrasts/corrections, double negation, quoted speech, and unclear consent are conservatively returned as `UNCERTAIN` with a request to restate the current fact. No cooking fact, focus, completion, or timer changes in that response; only the processed command/version is saved so retries remain idempotent. A voice acceptance requires one unambiguous offered timer; multiple offered timers can be individually accepted by explicit UI taps.

These are conservative Korean syntax/polarity checks, not a complete language understanding or action-to-evidence semantic proof. Dialects, omitted subjects, compound commands, instructive uses of past tense, and ambiguous clause boundaries can require a repeat or escape a syntax-only guard. The model must still map each reported action correctly, and source-order fallback actions can contain several operations. Users should use the completion button only when the whole source step is done; per-action model reports must not turn a reported sub-action into whole-step completion. Representative adversarial/model evaluations remain necessary; “all cooking situations” is not a validated guarantee.

The adapter asks the model to distinguish recipe-provided alternatives from general advice, consider the ingredient's role and whether cooking has already started, and account for stated allergy/dietary constraints. It must not claim unverified substitute amounts, food safety, or compatibility. Structured validation enforces references and explicit evidence; it cannot guarantee that a model's free-text advice is correct. Representative real kitchen/model evaluations are still required.

## Serving adjustments

`GET /v1/recipes/{id}/scaled-ingredients?servings=...` retains `originalQuantityText` and the existing `scaledQuantityText` field. Numeric arithmetic is exact, but changed-serving quantities have status `PROPORTIONAL_ESTIMATE`, not a verified cooking amount. Same-serving numeric quantities have status `UNCHANGED`. `UNPARSEABLE` and `BASE_SERVINGS_UNKNOWN` preserve uncertain amounts. Changed-serving responses include a warning that liquid, seasoning, evaporation, cookware, product instructions, and cooking time require separate contextual adjustment.

There are no recipe-name/ingredient-name lookup tables or invented nonlinear rules. A 550 ml baseline for one serving can mathematically produce 1100 ml for two, but that value is only a calculation reference, not a claim that two packages should use 1100 ml. The model receives both values and must verify product/recipe instructions or discuss the required cooking context rather than present an unverified 800 ml (or another value) as established fact.

## Photos and ambiguous questions

The model may select only existing `hasPhoto=true` source steps and must cite the selected step. The server rejects unavailable/uncited photo references and returns only stored source URLs. The model instructions prioritize the viewed step when relevant, require clarification when an ambiguous utterance has multiple plausible referents, and require disclosure when a different source step is shown. A chopping question must not be answered with an unrelated addition/plating photo described as chopping. If the original recipe has no appropriate photo, the reply should say so and return no photo. Text labels alone do not verify what an image visually depicts; visual matching quality remains an explicit evaluation target.

Browsing, ingredient/timer questions, and showing another step's photo never silently advance completion or focus. Missing step reports are left unknown, and explicit skips can be discussed without forcing a report for every recipe step.

## Video-derived recipes

Recipes imported from a YouTube link or a phone video have `sourceKind=VIDEO_AI` and use the same cooking session endpoints and evidence rules. Their source steps and ingredients were written by AI from the video's speech and on-screen text, then checked against a verbatim transcript (see [video recipe import](VIDEO_RECIPE_IMPORT.md)). The model receives `recipeSourceKind`; for `VIDEO_AI` it is told to say briefly that its source information comes from the video and to point the user to the video when an amount or time matters. Steps may carry `videoStartSeconds`, `videoEndSeconds`, and `keyFrameSeconds`. These fields only locate the step in the video; the session state does not track video playback.
