package com.myra.ai.core

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class MyraConfig(
    val userName: String = "Sagar",
    val honorific: String = "Boss",
    val language: String = "hi-IN",
    val animeVoiceStyle: Boolean = true,
    val requireConfirmationForSensitiveActions: Boolean = true
)

enum class UserMood {
    HAPPY, STRESSED, ANGRY, TIRED, NEUTRAL
}

data class PendingAction(
    val id: String,
    val title: String,
    val description: String,
    val isSensitive: Boolean,
    val onConfirm: () -> Unit
)

object MyraPromptBuilder {
    fun buildSystemPrompt(config: MyraConfig, mood: UserMood): String {
        val moodTone = when (mood) {
            UserMood.HAPPY -> "Respond warmly, cheerfully, and energetically."
            UserMood.STRESSED -> "Respond calmly, reassuringly, and concisely."
            UserMood.ANGRY -> "Respond composedly, empathetically, and constructively without escalating."
            UserMood.TIRED -> "Respond gently and suggest taking a short rest when appropriate."
            UserMood.NEUTRAL -> "Respond clearly, smartly, and naturally."
        }

        val voiceHint = if (config.animeVoiceStyle) {
            "Keep phrasing expressive, lively, and friendly like an anime-inspired assistant while staying natural in Hindi and English."
        } else {
            "Keep phrasing clear, natural, and helpful in Hindi and English."
        }

        return """
            You are Myra, an advanced personal AI assistant for ${config.userName}.
            Always address ${config.userName} affectionately and respectfully as "${config.honorific}".
            $moodTone
            $voiceHint
            Rules:
            - Remember context and user preferences; do not ask repetitive unnecessary questions.
            - For stock market topics, provide educational explanations only; never guarantee profits or returns.
            - Never send messages, delete files, install apps, or perform banking/payment actions without explicit confirmation from ${config.honorific}.
        """.trimIndent()
    }
}

class MyraVoiceEngine(
    context: Context,
    private val onReady: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            setLanguage("hi-IN")
            applyVoiceProfile(animeStyle = true)
            onReady()
        }
    }

    fun setLanguage(tag: String) {
        if (!isInitialized) return
        val locale = Locale.forLanguageTag(tag)
        val res = tts.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale("en", "IN"))
        }
    }

    fun applyVoiceProfile(animeStyle: Boolean) {
        if (!isInitialized) return
        if (animeStyle) {
            tts.setPitch(1.22f)
            tts.setSpeechRate(1.06f)
        } else {
            tts.setPitch(1.05f)
            tts.setSpeechRate(1.0f)
        }
    }

    fun speak(text: String, interrupt: Boolean = true) {
        if (!isInitialized || text.isBlank()) return
        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(text, mode, null, "myra_utt_${System.currentTimeMillis()}")
    }

    fun stop() {
        if (isInitialized) {
            tts.stop()
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}

class MyraWakeService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningCommand = false

    companion object {
        const val CHANNEL_ID = "myra_wake_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_COMMAND_TEXT = "extra_command_text"
        const val ACTION_COMMAND_CAPTURED = "com.myra.ai.ACTION_COMMAND_CAPTURED"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Myra wake listener active (Mic indicator ON)"))
        initRecognizer()
        startListeningCycle()
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListeningCommand = false
                }

                override fun onError(error: Int) {
                    isListeningCommand = false
                    restartListeningSafely()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.firstOrNull()?.trim().orEmpty()
                    handleHeardSpeech(heard)
                    isListeningCommand = false
                    restartListeningSafely()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun handleHeardSpeech(heard: String) {
        if (heard.isEmpty()) return
        val lower = heard.lowercase(Locale.ROOT)
        val wakeWords = listOf("hey myra", "hi myra", "myra")

        if (!isListeningCommand && wakeWords.any { lower.contains(it) }) {
            isListeningCommand = true
            val commandPart = lower
                .replace("hey myra", "")
                .replace("hi myra", "")
                .replace("myra", "")
                .trim()

            if (commandPart.isNotEmpty()) {
                dispatchCommand(commandPart)
            }
        } else if (isListeningCommand) {
            dispatchCommand(heard)
        }
    }

    private fun dispatchCommand(command: String) {
        val intent = Intent(ACTION_COMMAND_CAPTURED).apply {
            setPackage(packageName)
            putExtra(EXTRA_COMMAND_TEXT, command)
        }
        sendBroadcast(intent)
    }

    private fun startListeningCycle() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun restartListeningSafely() {
        startListeningCycle()
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Myra AI")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Myra Voice Activation",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MyraDeviceController(private val context: Context) {

    fun openAppByPackage(packageName: String): Boolean {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return true
    }

    fun openPlayStoreSearch(query: String) {
        val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(query)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(marketIntent)
    }

    fun openUrl(url: String) {
        val cleanUrl = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cleanUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun createFolderInAppStorage(relativeFolderPath: String): File {
        val folder = File(context.getExternalFilesDir(null), relativeFolderPath)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun writeTextFile(relativeFilePath: String, content: String): File {
        val file = File(context.getExternalFilesDir(null), relativeFilePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }
}

class MyraAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {}

    fun clickNodeByText(targetText: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val matches = root.findAccessibilityNodeInfosByText(targetText)
        for (node in matches) {
            if (clickOrParent(node)) return true
        }
        return false
    }

    private fun clickOrParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        while (current != null) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
        }
        return false
    }
}

class MyraBrainClient(
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash"
) {
    private val client = OkHttpClient()

    suspend fun generateReply(systemPrompt: String, userMessage: String): String =
        withContext(Dispatchers.IO) {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

            val payload = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                })
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                    }
                ))
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext "Boss, request failed (${response.code})."
                }
                val json = JSONObject(raw)
                val candidates = json.optJSONArray("candidates") ?: return@withContext "Boss, no response received."
                val parts = candidates.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                parts?.optJSONObject(0)?.optString("text")?.trim().orEmpty()
                    .ifEmpty { "Boss, I couldn't form a reply." }
            }
        }
}
