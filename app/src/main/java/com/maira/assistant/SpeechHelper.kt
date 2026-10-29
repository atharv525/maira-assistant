package com.maira.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Thin wrapper over Android SpeechRecognizer (hi-IN, prefer offline). Must be used on the main thread. */
class SpeechHelper(
    private val ctx: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (List<String>) -> Unit,
    private val onError: (Int) -> Unit,
    private val onReady: () -> Unit = {},
    private val onEnd: () -> Unit = {}
) : RecognitionListener {

    private var sr: SpeechRecognizer? = null
    var listening = false; private set

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    private fun ensure(): SpeechRecognizer {
        sr?.let { return it }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(this)
        sr = r
        return r
    }

    fun start(longListen: Boolean = false) {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-IN"))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (longListen) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            }
        }
        try {
            listening = true
            ensure().startListening(i)
        } catch (e: Exception) {
            listening = false
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stop() { try { sr?.stopListening() } catch (_: Exception) {}; listening = false }
    fun cancel() { try { sr?.cancel() } catch (_: Exception) {}; listening = false }
    fun destroy() { try { sr?.destroy() } catch (_: Exception) {}; sr = null; listening = false }

    override fun onReadyForSpeech(params: Bundle?) { onReady() }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() { onEnd() }
    override fun onError(error: Int) { listening = false; onError.invoke(error) }
    override fun onResults(results: Bundle?) {
        listening = false
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
        onFinal(list)
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        list?.firstOrNull()?.let { if (it.isNotBlank()) onPartial(it) }
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        fun errorText(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "कुछ सुनाई नहीं दिया, फिर से बोलिए।"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                "ऑफ़लाइन हिंदी पहचान डाउनलोड नहीं है। सेटअप में 'ऑफ़लाइन हिंदी पहचान' बटन दबाइए।"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "माइक की अनुमति दीजिए।"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "माइक व्यस्त है, एक पल रुकिए।"
            12, 13 -> "हिंदी भाषा पैक उपलब्ध नहीं है। सेटअप में ऑफ़लाइन हिंदी डाउनलोड कीजिए।"
            else -> "आवाज़ पहचानने में दिक्कत आई (कोड $code)।"
        }
    }
}
