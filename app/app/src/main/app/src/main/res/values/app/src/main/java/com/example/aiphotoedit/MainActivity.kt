package com.example.aiphotoedit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_MODEL = "gemini-2.5-flash-image"

const val SYSTEM_PROMPT = """You are a professional photo editor. The user sends a photo and an editing instruction, usually in Persian (Farsi).
Understand the Persian instruction precisely, then edit the provided photo accordingly and return the edited image.
Rules:
- Change ONLY what the user asks. Keep everything else identical: faces, identity, pose, clothing, lighting, composition and image proportions.
- Keep the result photorealistic unless the user asks for a specific style (cartoon, painting, anime, etc.).
- If the user asks to remove an object, fill the area naturally with matching background.
- If the user asks to add text in Persian, write it in correct, readable Persian script, right-to-left, with connected letters.
- Never add watermarks or extra text that was not requested.
- If the request is unclear, make the most reasonable interpretation and still return an edited image."""

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL) }
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }

    var original by remember { mutableStateOf<Bitmap?>(null) }
    var current by remember { mutableStateOf<Bitmap?>(null) }
    val history = remember { mutableStateListOf<Bitmap>() }
    var showOriginal by remember { mutableStateOf(false) }
    var instruction by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                val b = loadBitmap(ctx, uri)
                original = b; current = b; history.clear(); error = null
            } catch (e: Exception) { error = "خواندن عکس ناموفق بود" }
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("ویرایشگر هوشمند عکس", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { showSettings = !showSettings }) { Text("تنظیمات") }
        }

        if (showSettings) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("کلید رایگان Gemini را از aistudio.google.com/apikey بگیرید و اینجا بچسبانید.")
                    OutlinedTextField(apiKey, { apiKey = it.trim() }, label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it.trim() }, label = { Text("نام مدل") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = {
                        prefs.edit().putString("key", apiKey).putString("model", model).apply()
                        showSettings = false
                    }) { Text("ذخیره") }
                }
            }
        }

        Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (current == null) "انتخاب عکس" else "انتخاب عکس دیگر")
        }

        val shown = if (showOriginal) original else current
        if (shown != null) {
            Image(shown.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
            if (history.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(showOriginal, { showOriginal = it })
                    Spacer(Modifier.width(8.dp))
                    Text("نمایش عکس اصلی")
                }
            }

            OutlinedTextField(
                instruction, { instruction = it },
                label = { Text("چه تغییری بدهم؟ (مثلاً: پس‌زمینه را ساحل غروب کن)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2
            )

            Button(
                enabled = !loading && instruction.isNotBlank() && !showOriginal,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (apiKey.isBlank()) { showSettings = true; error = "اول کلید API را وارد کنید"; return@Button }
                    val src = current ?: return@Button
                    loading = true; error = null
                    scope.launch {
                        try {
                            val out = withContext(Dispatchers.IO) { editWithGemini(src, instruction, apiKey, model) }
                            history.add(src); current = out; instruction = ""
                        } catch (e: Exception) {
                            error = e.message ?: "خطای ناشناخته"
                        } finally { loading = false }
                    }
                }
            ) { Text(if (loading) "در حال ویرایش..." else "اعمال تغییر") }

            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = history.isNotEmpty() && !loading,
                    onClick = { current = history.removeAt(history.lastIndex) },
                    modifier = Modifier.weight(1f)
                ) { Text("بازگشت") }
                Button(
                    enabled = current != null && !loading,
                    onClick = {
                        val ok = saveToGallery(ctx, current!!)
                        Toast.makeText(ctx, if (ok) "در گالری ذخیره شد" else "ذخیره ناموفق بود", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("ذخیره در گالری") }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 1024): Bitmap {
    val src = ImageDecoder.createSource(ctx.contentResolver, uri)
    return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
        val w = info.size.width; val h = info.size.height
        val scale = maxSide.toFloat() / maxOf(w, h)
        if (scale < 1f) decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

fun editWithGemini(bitmap: Bitmap, instruction: String, apiKey: String, model: String): Bitmap {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

    val body = JSONObject().apply {
        put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))))
        put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("text", instruction))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
        put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
    }

    val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 30000
    conn.readTimeout = 120000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    conn.setRequestProperty("x-goog-api-key", apiKey)
    conn.outputStream.use { it.write(body.toString().toByteArray()) }

    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    if (code !in 200..299) {
        throw Exception(when (code) {
            429 -> "سهمیه رایگان این مدل تمام شده یا مدل در پلن رایگان فعال نیست. کمی بعد دوباره امتحان کنید یا نام مدل را در تنظیمات عوض کنید."
            400, 403 -> "کلید API یا نام مدل نامعتبر است ($code)"
            else -> "خطای سرور ($code)"
        })
    }

    val json = JSONObject(text)
    val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
    var reply = ""
    if (parts != null) for (i in 0 until parts.length()) {
        val p = parts.getJSONObject(i)
        val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data")
        if (inline != null) {
            val bytes = Base64.decode(inline.getString("data"), Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }
        reply += p.optString("text", "")
    }
    throw Exception(if (reply.isNotBlank()) "مدل عکس برنگرداند: $reply" else "مدل عکسی برنگرداند (احتمالاً فیلتر ایمنی). دستور را تغییر دهید.")
}

fun saveToGallery(ctx: Context, bmp: Bitmap): Boolean = try {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "edit_${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AIPhotoEdit")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    true
} catch (e: Exception) { false }
