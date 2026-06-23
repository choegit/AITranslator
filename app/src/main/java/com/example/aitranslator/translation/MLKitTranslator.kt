package com.example.aitranslator.translation

import com.example.aitranslator.model.Language
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.mlkit.nl.translate.Translator as MlTranslator

/**
 * Real on-device translation backed by ML Kit.
 *
 * One [MlTranslator] client is created and cached per source→target pair. The
 * per-pair model (~30 MB) is downloaded on first use; subsequent translations are
 * fully offline. ML Kit's callback-based [Task]s are bridged to suspend functions.
 */
class MLKitTranslator : Translator {

    private val clients = ConcurrentHashMap<String, MlTranslator>()

    override suspend fun translate(text: String, from: Language, to: Language): String {
        val source = TranslateLanguage.fromLanguageTag(from.code)
            ?: error("Translation source ${from.code} not supported")
        val target = TranslateLanguage.fromLanguageTag(to.code)
            ?: error("Translation target ${to.code} not supported")

        val client = clients.getOrPut("$source-$target") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build(),
            )
        }

        // No-op once the model is present; downloads it (any network) on first use.
        client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return client.translate(text).await()
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
