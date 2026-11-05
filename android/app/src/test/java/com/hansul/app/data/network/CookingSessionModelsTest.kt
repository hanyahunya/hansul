package com.hansul.app.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookingSessionModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes timer duration seconds and approximate evidence`() {
        val payload =
            """
            {
              "id": "55fb672f-4f30-42d4-a9ce-bb0723e102de",
              "plan": {
                "timerCues": {
                  "simmer": {
                    "id": "simmer",
                    "durationSeconds": 300,
                    "sourceStepNumber": 4,
                    "approximate": true
                  }
                },
                "futureField": true
              },
              "timerDeadlines": [],
              "serverOnlyField": "ignored"
            }
            """.trimIndent()

        val session = json.decodeFromString<CookingSessionResponse>(payload).toDomain()
        val cue = session.timerCues.getValue("simmer")

        assertEquals(300, cue.durationSeconds)
        assertEquals(4, cue.sourceStepNumber)
        assertTrue(cue.approximate)
    }

    @Test
    fun `also accepts ISO duration emitted by generic Java time serialization`() {
        val payload =
            """
            {
              "id": "55fb672f-4f30-42d4-a9ce-bb0723e102de",
              "plan": {
                "timerCues": {
                  "boil": {
                    "id": "boil",
                    "duration": "PT2M30S",
                    "sourceStepNumber": 2
                  }
                }
              }
            }
            """.trimIndent()

        val cue = json.decodeFromString<CookingSessionResponse>(payload)
            .toDomain()
            .timerCues
            .getValue("boil")

        assertEquals(150, cue.durationSeconds)
    }
}

