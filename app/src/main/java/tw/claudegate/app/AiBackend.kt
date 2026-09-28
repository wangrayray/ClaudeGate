package tw.claudegate.app

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 對話中的一句。fromUser=false 時 text 是 AI 回的 JSON 原文 */
data class Turn(val fromUser: Boolean, val text: String)

/** 呼叫失敗，message 直接顯示給使用者 */
class AiException(message: String) : Exception(message)

/**
 * 一家 AI 的呼叫方式。輸入 system prompt、對話、JSON schema，
 * 回傳一定符合 schema 的 JSON 字串。阻塞呼叫，請在 Dispatchers.IO 上用。
 */
interface AiBackend {
    /** light = true：離題偵測這種簡單、頻繁的判斷，請用較省的設定 */
    fun completeJson(
        model: String,
        system: String,
        turns: List<Turn>,
        schema: Map<String, Any>,
        maxTokens: Int,
        light: Boolean,
    ): String

    companion object {
        fun of(p: Provider, apiKey: String): AiBackend {
            if (apiKey.isBlank()) throw AiException("還沒設定 ${p.displayName} 的 API 金鑰，請到設定頁填入。")
            return when (p) {
                Provider.CLAUDE -> ClaudeBackend.get(apiKey)
                Provider.GEMINI -> GeminiBackend(apiKey)
            }
        }
    }
}

// ============================================================ Claude

/** Claude：官方 Anthropic Java SDK，結構化輸出 */
class ClaudeBackend private constructor(private val client: AnthropicClient) : AiBackend {

    override fun completeJson(
        model: String, system: String, turns: List<Turn>,
        schema: Map<String, Any>, maxTokens: Int, light: Boolean,
    ): String {
        val effort = if (light) OutputConfig.Effort.LOW else OutputConfig.Effort.MEDIUM
        val format = JsonOutputFormat.builder()
            .schema(JsonOutputFormat.Schema.builder()
                .apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                .build())
            .build()
        val output = OutputConfig.builder().format(format)
        // Haiku 4.5 不支援 effort 參數，送了會 400
        if (!model.startsWith("claude-haiku")) output.effort(effort)

        val b = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxTokens.toLong())
            .system(system)
            .outputConfig(output.build())
        if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")) {
            // 安全分類器偶爾誤判拒答時，由伺服器自動改用別的模型回答
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        for (t in turns) if (t.fromUser) b.addUserMessage(t.text) else b.addAssistantMessage(t.text)

        val msg = try {
            client.messages().create(b.build())
        } catch (e: UnauthorizedException) {
            throw AiException("Claude API 金鑰無效，請到設定頁檢查。")
        } catch (e: PermissionDeniedException) {
            throw AiException("這把 Claude API 金鑰沒有使用這個模型的權限。")
        } catch (e: NotFoundException) {
            throw AiException("找不到模型「$model」，請到設定頁檢查模型名稱。")
        } catch (e: RateLimitException) {
            throw AiException("Claude 太多請求了，等一下再試。")
        } catch (e: BadRequestException) {
            throw AiException("請求被 Claude 拒絕（400）：${e.message?.take(200)}")
        } catch (e: AnthropicServiceException) {
            throw AiException("Claude 暫時無法回應（${e.statusCode()}），等一下再試。")
        } catch (e: AnthropicIoException) {
            throw AiException("連不上 Claude，請確認網路。")
        }

        when (msg.stopReason().orElse(null)) {
            StopReason.REFUSAL -> throw AiException("Claude 拒絕回答這個請求。")
            StopReason.MAX_TOKENS -> throw AiException("Claude 的回覆太長被截斷了，請再送一次。")
            else -> Unit
        }
        return msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
    }

    companion object {
        @Volatile
        private var cached: Pair<String, ClaudeBackend>? = null

        /** 同一把金鑰共用一個 client（裡面有連線池） */
        fun get(apiKey: String): ClaudeBackend = synchronized(this) {
            cached?.let { (k, c) -> if (k == apiKey) return c }
            ClaudeBackend(AnthropicOkHttpClient.builder().apiKey(apiKey).build())
                .also { cached = apiKey to it }
        }
    }
}

// ============================================================ Gemini

/**
 * Gemini：REST generateContent，responseJsonSchema 結構化輸出。
 * 不用 Google 的 Java SDK —— 它為伺服器端設計、相依很重，這裡一個 POST 就夠。
 */
class GeminiBackend(
    private val apiKey: String,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) : AiBackend {

    override fun completeJson(
        model: String, system: String, turns: List<Turn>,
        schema: Map<String, Any>, maxTokens: Int, light: Boolean,
    ): String {
        val contents = JSONArray()
        for (t in turns) {
            contents.put(JSONObject()
                .put("role", if (t.fromUser) "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", t.text))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("responseJsonSchema", JSONObject(schema))
                .put("maxOutputTokens", maxTokens))

        val req = Request.Builder()
            .url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (code, text) = try {
            http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            throw AiException("連不上 Gemini，請確認網路。")
        }
        if (code != 200) throw errorFor(code, text, model)

        val json = try { JSONObject(text) } catch (e: Exception) {
            throw AiException("看不懂 Gemini 的回覆，請再送一次。")
        }
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotEmpty() }?.let {
            throw AiException("Gemini 拒絕回答這個請求（$it）。")
        }
        val cand = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiException("Gemini 沒有回覆內容，請再送一次。")
        when (val reason = cand.optString("finishReason")) {
            "", "STOP" -> Unit
            "MAX_TOKENS" -> throw AiException("Gemini 的回覆太長被截斷了，請再送一次。")
            else -> throw AiException("Gemini 拒絕回答這個請求（$reason）。")
        }
        val parts = cand.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        return buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (p.optBoolean("thought")) continue   // 思考摘要不是答案
                append(p.optString("text"))
            }
        }.trim()
    }

    private fun errorFor(code: Int, body: String, model: String): AiException {
        val err = try { JSONObject(body).optJSONObject("error") } catch (e: Exception) { null }
        val msg = err?.optString("message").orEmpty()
        return when {
            code == 400 && (msg.contains("API key", ignoreCase = true) || body.contains("API_KEY_INVALID")) ->
                AiException("Gemini API 金鑰無效，請到設定頁檢查。")
            code == 400 -> AiException("請求被 Gemini 拒絕（400）：${msg.take(200)}")
            code == 403 -> AiException("這把 Gemini API 金鑰沒有權限（403）：${msg.take(120)}")
            code == 404 -> AiException("找不到模型「$model」，請到設定頁檢查模型名稱。")
            code == 429 -> AiException("Gemini 的免費額度用完了或請求太頻繁，等一下再試。")
            else -> AiException("Gemini 暫時無法回應（$code），等一下再試。")
        }
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
