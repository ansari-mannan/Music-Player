package com.andres.musicplayer

import com.andres.musicplayer.data.youtube.ResolutionFailure
import com.andres.musicplayer.data.youtube.StreamResolutionException
import com.andres.musicplayer.data.youtube.classifyResolutionFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.net.UnknownHostException

/**
 * Telling "the extractor has fallen behind YouTube" apart from "that video is gone".
 *
 * These two produce identical-looking failures at the call site but have opposite
 * answers: one is a library version bump and affects every download at once, the other
 * is one video and nothing anybody can do. Reporting the first as the second hides a
 * broken app; reporting the second as the first sends the user chasing an update that
 * will not help.
 */
class ResolutionFailureTest {

    @Test
    fun `a parsing failure means the extractor no longer understands YouTube`() {
        assertEquals(
            ResolutionFailure.EXTRACTOR_OUT_OF_DATE,
            classifyResolutionFailure(ParsingException("Could not parse the player response")),
        )
        assertEquals(
            ResolutionFailure.EXTRACTOR_OUT_OF_DATE,
            classifyResolutionFailure(ExtractionException("could not get signature")),
        )
    }

    @Test
    fun `an unavailable video is never blamed on the extractor`() {
        // The trap: ContentNotAvailableException *extends* ParsingException, so a
        // classifier that checked for parsing failures first would call every deleted
        // video a broken extractor.
        listOf(
            ContentNotAvailableException("gone"),
            PrivateContentException("private"),
            GeographicRestrictionException("not in your country"),
            AgeRestrictedContentException("18+"),
            PaidContentException("rental"),
            YoutubeMusicPremiumContentException(),
            AccountTerminatedException("channel closed"),
        ).forEach { e ->
            assertEquals(
                "${e.javaClass.simpleName} should read as an unavailable video",
                ResolutionFailure.VIDEO_UNAVAILABLE,
                classifyResolutionFailure(e),
            )
        }
    }

    @Test
    fun `content the extractor cannot handle is its own answer`() {
        assertEquals(
            ResolutionFailure.NOT_DOWNLOADABLE,
            classifyResolutionFailure(ContentNotSupportedException("live stream")),
        )
    }

    @Test
    fun `bot checks are reported as bot checks, not as a broken app`() {
        assertEquals(
            ResolutionFailure.BLOCKED_BY_YOUTUBE,
            classifyResolutionFailure(ReCaptchaException("captcha", "https://youtube.com")),
        )
        assertEquals(
            ResolutionFailure.BLOCKED_BY_YOUTUBE,
            classifyResolutionFailure(
                SignInConfirmNotBotException("Sign in to confirm you're not a bot"),
            ),
        )
    }

    @Test
    fun `never reaching YouTube is a network problem, not a YouTube change`() {
        assertEquals(
            ResolutionFailure.NETWORK,
            classifyResolutionFailure(UnknownHostException("www.youtube.com")),
        )
        assertEquals(
            ResolutionFailure.NETWORK,
            classifyResolutionFailure(IOException("stream closed")),
        )
    }

    @Test
    fun `an unrecognised crash inside the extractor defaults to a YouTube change`() {
        // A NullPointerException or a JSON error from deep inside the extractor is
        // exactly what a server-side change looks like from out here.
        assertEquals(
            ResolutionFailure.EXTRACTOR_OUT_OF_DATE,
            classifyResolutionFailure(NullPointerException()),
        )
        assertEquals(
            ResolutionFailure.EXTRACTOR_OUT_OF_DATE,
            classifyResolutionFailure(IllegalStateException("unexpected json shape")),
        )
    }

    @Test
    fun `the extractor message points at the app, and no other one does`() {
        val extractorMessage = ResolutionFailure.EXTRACTOR_OUT_OF_DATE.userMessage
        assertTrue(
            "the whole point is that this message says an update may be needed",
            extractorMessage.contains("app update"),
        )
        ResolutionFailure.entries
            .filter { it != ResolutionFailure.EXTRACTOR_OUT_OF_DATE }
            .forEach { failure ->
                assertFalse(
                    "${failure.name} must not send the user looking for an app update",
                    failure.userMessage.contains("app update"),
                )
            }
    }

    @Test
    fun `every failure says something different`() {
        val messages = ResolutionFailure.entries.map { it.userMessage }
        assertEquals(
            "two failures sharing wording would be indistinguishable in the Failed tab",
            messages.size,
            messages.distinct().size,
        )
        messages.forEach { assertTrue(it.isNotBlank()) }
    }

    @Test
    fun `the exception carries the failure through to what the user reads`() {
        val e = StreamResolutionException(
            failure = ResolutionFailure.EXTRACTOR_OUT_OF_DATE,
            detail = "nsig extraction failed",
            cause = ParsingException("boom"),
        )
        assertSame(ResolutionFailure.EXTRACTOR_OUT_OF_DATE, e.failure)
        assertEquals(ResolutionFailure.EXTRACTOR_OUT_OF_DATE.userMessage, e.message)
        // The extractor's own words stay out of the user's way but survive for the log.
        assertEquals("nsig extraction failed", e.detail)
        assertNotEquals(e.message, e.detail)
    }
}
