package com.music.bitchord.data.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AiAlbumRecommendationsTest {

    @Test
    fun parsesValidStructuredAlbumPicks() {
        val json = """
            {
              "albums": [
                {
                  "artist": "Radiohead",
                  "album": "In Rainbows",
                  "why": "Warm art-rock songwriting and pristine production."
                },
                {
                  "artist": "Portishead",
                  "album": "Dummy",
                  "why": "Definitive 90s trip-hop album."
                }
              ]
            }
        """.trimIndent()

        val picks = AiAlbumRecommendationParser.parse(json)
        assertEquals(2, picks.size)
        assertEquals(
            AiAlbumPick(
                artist = "Radiohead",
                album = "In Rainbows",
                why = "Warm art-rock songwriting and pristine production.",
            ),
            picks[0],
        )
        assertEquals(
            AiAlbumPick(
                artist = "Portishead",
                album = "Dummy",
                why = "Definitive 90s trip-hop album.",
            ),
            picks[1],
        )
    }

    @Test
    fun trimsWhitespaceAndDefaultsMissingWhy() {
        val json = """
            {
              "albums": [
                {
                  "artist": "  Massive Attack  ",
                  "album": "  Mezzanine  "
                }
              ]
            }
        """.trimIndent()

        val picks = AiAlbumRecommendationParser.parse(json)
        assertEquals(1, picks.size)
        assertEquals("Massive Attack", picks[0].artist)
        assertEquals("Mezzanine", picks[0].album)
        assertEquals("", picks[0].why)
    }

    @Test
    fun filtersOutIncompleteItems() {
        val json = """
            {
              "albums": [
                {
                  "artist": "",
                  "album": "Album With No Artist"
                },
                {
                  "artist": "Artist With No Album",
                  "album": "   "
                },
                {
                  "why": "Only a why statement"
                },
                {
                  "artist": "Valid Artist",
                  "album": "Valid Album",
                  "why": "Complete entry"
                }
              ]
            }
        """.trimIndent()

        val picks = AiAlbumRecommendationParser.parse(json)
        assertEquals(1, picks.size)
        assertEquals("Valid Artist", picks[0].artist)
        assertEquals("Valid Album", picks[0].album)
        assertEquals("Complete entry", picks[0].why)
    }

    @Test
    fun toleratesFencedAndChattyModelResponses() {
        val chattyResponse = """
            Here are two albums matching your library profile:

            ```json
            {
              "albums": [
                {
                  "artist": "Miles Davis",
                  "album": "Kind of Blue",
                  "why": "Modal jazz benchmark."
                },
                {
                  "artist": "John Coltrane",
                  "album": "A Love Supreme",
                  "why": "Spiritual jazz masterpiece."
                }
              ]
            }
            ```

            Hope these fit your listening taste!
        """.trimIndent()

        val picks = AiAlbumRecommendationParser.parse(chattyResponse)
        assertEquals(2, picks.size)
        assertEquals("Miles Davis", picks[0].artist)
        assertEquals("Kind of Blue", picks[0].album)
        assertEquals("Modal jazz benchmark.", picks[0].why)
        assertEquals("John Coltrane", picks[1].artist)
        assertEquals("A Love Supreme", picks[1].album)
        assertEquals("Spiritual jazz masterpiece.", picks[1].why)
    }

    @Test
    fun extractsJsonObjectDirectly() {
        val obj = AiAlbumRecommendationParser.extractJsonObject(
            "Intro text\n```json\n{\"testKey\":\"testValue\"}\n```\nOutro text",
        )
        val value = obj["testKey"]?.let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        assertEquals("testValue", value)
    }

    @Test
    fun extractsSingleBalancedObjectWithBracesInsideStrings() {
        val picks = AiAlbumRecommendationParser.parse(
            "Before { not json } ```json {\"albums\":[{\"artist\":\"A {B}\",\"album\":\"C} D\"}]} ``` after",
        )
        assertEquals(listOf(AiAlbumPick("A {B}", "C} D")), picks)
    }

    @Test
    fun rejectsAmbiguousMultipleJsonObjects() {
        assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse("{\"albums\":[]} and {\"albums\":[]}")
        }
    }

    @Test
    fun rejectsMalformedEntryTypesInsteadOfDroppingThem() {
        assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse("""{"albums":[{"artist":7,"album":"A"},{"artist":"B","album":"C"}]}""")
        }
        assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse("""{"albums":[{"artist":"A","album":"B","why":false}]}""")
        }
    }

    @Test
    fun rejectsResponsesAndAlbumListsOverDocumentedBounds() {
        assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse(" ".repeat(65_537))
        }
        val tooMany = (0..50).joinToString(",") {
            "{\"artist\":\"A\",\"album\":\"B\"}"
        }
        assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse("{\"albums\":[$tooMany]}")
        }
    }

    @Test
    fun rejectsMalformedJsonWithoutJsonObject() {
        val noJson = "I am a language model and I cannot generate recommendations right now."
        val ex = assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse(noJson)
        }
        assertTrue(ex.message!!.contains("expected format"))
    }

    @Test
    fun rejectsSyntacticallyInvalidJson() {
        val invalidJson = "```json\n{ \"albums\": [ broken json syntax ...\n```"
        val ex = assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse(invalidJson)
        }
        assertTrue(ex.message!!.contains("expected format"))
    }

    @Test
    fun rejectsMissingAlbumsFieldInSchema() {
        val wrongSchema = """{"artists": ["Radiohead", "Portishead"]}"""
        val ex = assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse(wrongSchema)
        }
        assertTrue(ex.message!!.contains("expected format"))
    }

    @Test
    fun rejectsNonArrayAlbumsFieldInSchema() {
        val nonArraySchema = """{"albums": "not an array"}"""
        val ex = assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse(nonArraySchema)
        }
        assertTrue(ex.message!!.contains("expected format"))
    }

    @Test
    fun returnsEmptyListWhenAlbumsArrayIsEmpty() {
        val emptyAlbums = """{"albums": []}"""
        val picks = AiAlbumRecommendationParser.parse(emptyAlbums)
        assertEquals(emptyList(), picks)
    }

    @Test
    fun throwsOnEmptyListWhenRequireNonEmptyIsTrue() {
        val emptyAlbums = """{"albums": []}"""
        val ex = assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse(emptyAlbums, requireNonEmpty = true)
        }
        assertTrue(ex.message!!.contains("No recommendations came back"))
    }

    @Test
    fun throwsOnAllFilteredItemsWhenRequireNonEmptyIsTrue() {
        val onlyBlanks = """{"albums": [{"artist": "", "album": ""}]}"""
        val ex = assertFailsWith<AiException> {
            AiAlbumRecommendationParser.parse(onlyBlanks, requireNonEmpty = true)
        }
        assertTrue(ex.message!!.contains("No recommendations came back"))
    }
}
