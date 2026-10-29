package ai.closepaw.ui.capsule.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidRecognizerTest {
    private val context = mockk<Context>()
    private val speechRecognizer = mockk<SpeechRecognizer>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(SpeechRecognizer::class)
        every { SpeechRecognizer.isRecognitionAvailable(context) } returns true
        every { SpeechRecognizer.createSpeechRecognizer(context) } returns speechRecognizer
        every { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) } returns speechRecognizer
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `unavailable on-device recognition returns null even when generic recognition is available`() {
        every { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } returns false
        val factory = AndroidRecognizerFactory(context)

        assertThat(factory.isAvailable()).isFalse()
        assertThat(factory.create()).isNull()

        verify(exactly = 0) { SpeechRecognizer.isRecognitionAvailable(any()) }
        verify(exactly = 0) { SpeechRecognizer.createSpeechRecognizer(any()) }
        verify(exactly = 0) { SpeechRecognizer.createOnDeviceSpeechRecognizer(any()) }
    }

    @Test
    fun `available on-device recognition creates only the on-device recognizer`() {
        every { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } returns true
        val factory = AndroidRecognizerFactory(context)

        assertThat(factory.isAvailable()).isTrue()
        assertThat(factory.create()).isNotNull()

        verify(exactly = 1) { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
        verify(exactly = 0) { SpeechRecognizer.isRecognitionAvailable(any()) }
        verify(exactly = 0) { SpeechRecognizer.createSpeechRecognizer(any()) }
    }

    @Test
    fun `on-device creation failure never falls back to generic recognition`() {
        every { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } returns true
        val failure = UnsupportedOperationException("On-device recognition unavailable")
        every { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) } throws failure

        val result = runCatching { AndroidRecognizerFactory(context).create() }

        assertThat(result.exceptionOrNull()).isSameInstanceAs(failure)
        verify(exactly = 0) { SpeechRecognizer.createSpeechRecognizer(any()) }
    }

    @Test
    fun `start requests offline recognition while preserving session options`() {
        every { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) } returns true
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers { self as Intent }
        val recognizer = AndroidRecognizerFactory(context).create()!!

        recognizer.start("en-US", mockk(relaxed = true))

        verify { anyConstructed<Intent>().putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
        verify { anyConstructed<Intent>().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true) }
        verify { anyConstructed<Intent>().putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US") }
        verify {
            anyConstructed<Intent>().putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
        }
        verify(exactly = 1) { speechRecognizer.startListening(any()) }
    }
}
