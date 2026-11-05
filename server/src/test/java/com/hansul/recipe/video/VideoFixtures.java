package com.hansul.recipe.video;

import java.time.Duration;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * A short kimchi fried rice video: the cook says the amounts in Korean words ("두 스푼", "삼 분", "반 스푼")
 * while the screen and the description show some of them as digits.
 */
final class VideoFixtures {

    static final String VIDEO_ID = "AbCdEfGhIjK";
    static final String WATCH_URL = "https://www.youtube.com/watch?v=" + VIDEO_ID;
    static final String THUMBNAIL = "https://i.ytimg.com/vi/" + VIDEO_ID + "/maxresdefault.jpg";
    static final String DESCRIPTION = """
            초간단 김치볶음밥 레시피

            [재료] 2인분
            신김치 1컵
            밥 2공기
            대파 1대
            식용유 2스푼
            설탕 0.5스푼
            참기름 약간

            #김치볶음밥 #자취요리""";

    static final String TRANSCRIPT_JSON = """
            {"isCookingVideo": true, "language": "ko", "segments": [
              {"startSeconds": 0, "endSeconds": 6.5,
               "speech": "안녕하세요 오늘은 김치볶음밥을 만들어 볼게요", "screenText": "초간단 김치볶음밥"},
              {"startSeconds": 6.5, "endSeconds": 15,
               "speech": "먼저 대파 한 대를 송송 썰어 줄게요", "screenText": ""},
              {"startSeconds": 15, "endSeconds": 28,
               "speech": "팬에 식용유 두 스푼을 두르고 대파를 넣어 파기름을 내 주세요", "screenText": "식용유 2스푼"},
              {"startSeconds": 28, "endSeconds": 45,
               "speech": "신김치 한 컵을 넣고 중불에서 삼 분 정도 볶아 줍니다", "screenText": ""},
              {"startSeconds": 45, "endSeconds": 58,
               "speech": "설탕 반 스푼 넣어 주시면 신맛이 줄어요", "screenText": "설탕 0.5스푼"},
              {"startSeconds": 58, "endSeconds": 75.5,
               "speech": "밥 두 공기를 넣고 잘 섞어 주세요", "screenText": "밥 2공기"},
              {"startSeconds": 75.5, "endSeconds": 90,
               "speech": "마지막으로 참기름 한 바퀴 둘러 주면 완성입니다", "screenText": ""}
            ]}""";

    static final String NOT_COOKING_JSON = """
            {"isCookingVideo": false, "language": "ko", "segments": [
              {"startSeconds": 0, "endSeconds": 12, "speech": "오늘은 제 자취방을 소개해 드릴게요", "screenText": "랜선 집들이"}
            ]}""";

    /** A draft whose every quote and number is backed: description lines are d1..d9, segments s1..s7. */
    static final String RECIPE_JSON = """
            {"title": "초간단 김치볶음밥",
             "servings": {"text": "2인분", "source": "DESCRIPTION", "ref": "d2", "quote": "2인분"},
             "ingredients": [
               {"name": "신김치", "quantityText": "1컵", "ref": "d3", "quote": "신김치 1컵"},
               {"name": "밥", "quantityText": "2공기", "ref": "d4", "quote": "밥 2공기"},
               {"name": "대파", "quantityText": "1대", "ref": "d5", "quote": "대파 1대"},
               {"name": "식용유", "quantityText": "2스푼", "ref": "s3", "quote": "식용유 두 스푼"},
               {"name": "설탕", "quantityText": "0.5스푼", "ref": "d7", "quote": "설탕 0.5스푼"},
               {"name": "참기름", "quantityText": "약간", "ref": "d8", "quote": "참기름 약간"}
             ],
             "steps": [
               {"text": "대파 1대를 송송 썰어주세요.", "segmentIds": ["s2"],
                "startSeconds": 6.5, "endSeconds": 15, "keyFrameSeconds": 11, "quote": "대파 한 대를 송송 썰어"},
               {"text": "팬에 식용유 2스푼을 두르고 대파를 넣어 파기름을 내주세요.", "segmentIds": ["s3"],
                "startSeconds": 15, "endSeconds": 28, "keyFrameSeconds": 22.4, "quote": "식용유 두 스푼을 두르고"},
               {"text": "신김치 1컵을 넣고 중불에서 3분 정도 볶아주세요.", "segmentIds": ["s4"],
                "startSeconds": 28, "endSeconds": 45, "keyFrameSeconds": 40,
                "quote": "신김치 한 컵을 넣고 중불에서 삼 분 정도 볶아"},
               {"text": "설탕 0.5스푼을 넣어 신맛을 줄여주세요.", "segmentIds": ["s5"],
                "startSeconds": 45, "endSeconds": 58, "keyFrameSeconds": 50, "quote": "설탕 반 스푼 넣어"},
               {"text": "밥 2공기를 넣고 잘 섞어주세요.", "segmentIds": ["s6"],
                "startSeconds": 58, "endSeconds": 75.5, "keyFrameSeconds": 66, "quote": "밥 두 공기를 넣고"},
               {"text": "참기름을 한 바퀴 둘러 마무리해주세요.", "segmentIds": ["s7"],
                "startSeconds": 75.5, "endSeconds": 90, "keyFrameSeconds": 85, "quote": "참기름 한 바퀴 둘러"}
             ]}""";

    /** A phone video has no description, so its ingredients cite segments only. */
    static final String UPLOAD_RECIPE_JSON = """
            {"title": "초간단 김치볶음밥",
             "servings": {"text": "", "source": "NONE", "ref": "", "quote": ""},
             "ingredients": [
               {"name": "대파", "quantityText": "1대", "ref": "s2", "quote": "대파 한 대를"},
               {"name": "식용유", "quantityText": "2스푼", "ref": "s3", "quote": "식용유 두 스푼"},
               {"name": "신김치", "quantityText": "1컵", "ref": "s4", "quote": "신김치 한 컵을"},
               {"name": "설탕", "quantityText": "0.5스푼", "ref": "s5", "quote": "설탕 0.5스푼"},
               {"name": "밥", "quantityText": "2공기", "ref": "s6", "quote": "밥 2공기"},
               {"name": "참기름", "quantityText": "", "ref": "s7", "quote": "참기름 한 바퀴"}
             ],
             "steps": [
               {"text": "대파 1대를 송송 썰어주세요.", "segmentIds": ["s2"],
                "startSeconds": 6.5, "endSeconds": 15, "keyFrameSeconds": 11, "quote": "대파 한 대를 송송 썰어"},
               {"text": "신김치 1컵을 넣고 중불에서 3분 정도 볶아주세요.", "segmentIds": ["s3", "s4"],
                "startSeconds": 15, "endSeconds": 45, "keyFrameSeconds": 40,
                "quote": "신김치 한 컵을 넣고 중불에서 삼 분 정도 볶아"},
               {"text": "밥 2공기를 넣고 잘 섞어주세요.", "segmentIds": ["s6"],
                "startSeconds": 58, "endSeconds": 75.5, "keyFrameSeconds": 66, "quote": "밥 두 공기를 넣고"}
             ]}""";

    static final String SAID_THREE_MINUTES = "신김치 1컵을 넣고 중불에서 3분 정도 볶아주세요.";
    static final String WROTE_FIVE_MINUTES = "신김치 1컵을 넣고 중불에서 5분 볶아주세요.";
    /** The same draft with a cooking time the video never says. */
    static final String UNGROUNDED_RECIPE_JSON = RECIPE_JSON.replace(SAID_THREE_MINUTES, WROTE_FIVE_MINUTES);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private VideoFixtures() {
    }

    static List<VideoTranscript.Segment> segments() {
        return VideoTranscriptPass.sanitize(MAPPER.readTree(TRANSCRIPT_JSON).path("segments"));
    }

    static VideoSources sources() {
        return new VideoSources(segments(), DESCRIPTION);
    }

    static VideoRecipeDraft draft(String json) {
        return VideoRecipeStructuringPass.draft(MAPPER.readTree(json));
    }

    static VideoMetadata metadata() {
        return new VideoMetadata(VIDEO_ID, "자취생 초간단 김치볶음밥", "한술키친", DESCRIPTION, 95, THUMBNAIL, true);
    }

    static VideoImportProperties properties() {
        return new VideoImportProperties(
                new VideoImportProperties.Gemini(
                        "http://127.0.0.1:1", "test-key", "gemini-test", "", "", 0, 0, Duration.ofSeconds(5), true),
                null,
                1800,
                10,
                2,
                null
        );
    }
}
