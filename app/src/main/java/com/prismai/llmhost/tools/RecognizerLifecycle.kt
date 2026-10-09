package com.prismai.llmhost.tools

/**
 * Tracks whether a SpeechRecognizer is live and requests release exactly once.
 *
 * VoiceIoManager assigned a recognizer in startListening, then onResults and
 * onError both set isListening = false without destroying it. Because
 * stopListening() returns early when !isListening, neither stopListening() nor
 * shutdown() could release it - the recognizer leaked on the ordinary success
 * path, not only on abandonment.
 */
class RecognizerLifecycle {
    var isCreated: Boolean = false
        private set
    var releaseRequested: Boolean = false
        private set
    var releaseCount: Int = 0
        private set

    fun onRecognizerCreated() {
        isCreated = true
        releaseRequested = false
    }

    /** A session ended (results or error); release the recognizer. */
    fun onTerminated() {
        if (isCreated && !releaseRequested) {
            releaseRequested = true
            releaseCount++
        }
    }

    fun shutdown() {
        onTerminated()
        isCreated = false
    }
}
