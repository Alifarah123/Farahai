package com.example.aiphotoedit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import java.net.URLEncoder

const val DEFAULT_MODEL = "kontext"

const val TRANSLATE_SYSTEM = """Convert the user's Persian photo-editing instruction into one short, precise English instruction for an image editing model. Output only the English instruction, nothing else."""

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
    var geminiKey by remember { mutableStateOf(prefs.getString("key", "") ?: "") }
    var polKey by remember { mutableStateOf(prefs.getString("polkey", "") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("pmodel", DEFAULT_MODEL) ?: DEFAULT_MODEL) }
    var showSettings by remember { mutableStateOf(polKey.isBlank()) }

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
                    Text("کلید Pollinations را از enter.pollinations.ai بگیرید (با حساب گیت‌هاب وارد شوید).")
                    OutlinedTextField(polKey, { polKey = it.trim() }, label = { Text("کلید Pollinations") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(geminiKey, { geminiKey = it.trim() }, label = { Text("کلید Gemini (برای ترجمه دستور فارسی)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(model, { model = it.trim() }, label = { Text("نام مدل (kontext یا klein)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("توجه: برای ویرایش، عکس یک ساعت روی یک سرور موقت آپلود می‌شود.")
                    Button(onClick = {
                        prefs.edit().putString("key", geminiKey).putString("polkey", polKey).putString("pmodel", model).apply()
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
                    if (polKey.isBlank()) { showSettings = true; error = "اول کلید Pollinations را وارد کنید"; return@Button }
                    val src = current ?: return@Button
                    loading = true; error = null
                    scope.launch {
                        try {
                            val out = withContext(Dispatchers.IO) { editWithPollinations(src, instruction, polKey, geminiKey, model) }
                            history.add(src); current = out; instruction = ""
                        } catch (e: Exception) {
                            error = e.message ?: "خطای ناشناخته"
                        } finally { loading = false }
                    }
                }
            ) { Text(if (loading) "در حال ویرایش (تا یک دقیقه)..." else "اعمال تغییر") }

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

fun jpegBytes(bmp: Bitmap): ByteArray {
    val b = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 90, b)
    return b.toByteArray()
}

fun translateToEnglish(text: String, geminiKey: String): String {
    if (geminiKey.isBlank()) return text
    return try {
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", TRANSLATE_SYSTEM))))
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
        }
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 40000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", geminiKey)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode !in 200..299) {
            text
        } else {
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val out = json.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0).getString("text").trim()
            if (out.isBlank()) text else out
        }
    } catch (e: Exception) { text }
}

fun uploadTemp(bytes: ByteArray): String {
    val boundary = "----B" + System.currentTimeMillis()
    val conn = URL("https://litterbox.catbox.moe/resources/internals/api.php").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.connectTimeout = 30000
    conn.readTimeout = 60000
    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    conn.outputStream.use { os ->
        fun field(n: String, v: String) {
            os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray())
        }
        field("reqtype", "fileupload")
        field("time", "1h")
        os.write("--$boundary\r\nContent-Disposition: form-data; name=\"fileToUpload\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
        os.write(bytes)
        os.write("\r\n--$boundary--\r\n".toByteArray())
    }
    val code = conn.responseCode
    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }?.trim() ?: ""
    if (code !in 200..299 || !text.startsWith("http")) throw Exception("آپلود موقت عکس ناموفق بود ($code). VPN را بررسی کنید.")
    return text
}

fun editWithPollinations(bitmap: Bitmap, instruction: String, polKey: String, geminiKey: String, model: String): Bitmap {
    val english = translateToEnglish(instruction, geminiKey)
    val prompt = "$english. Keep the person's face, identity, pose and all other details unchanged."
    val imageUrl = uploadTemp(jpegBytes(bitmap))
    val url = "https://gen.pollinations.ai/image/" +
        URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
        "?model=" + model +
        "&image=" + URLEncoder.encode(imageUrl, "UTF-8") +
        "&nologo=true&private=true" +
        "&key=" + URLEncoder.encode(polKey, "UTF-8")

    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 30000
    conn.readTimeout = 180000
    val code = conn.responseCode
    val data = (if (code in 200..299) conn.inputStream else conn.errorStream)?.readBytes() ?: ByteArray(0)
    if (code !in 200..299) {
        val snippet = String(data).take(150)
        throw Exception(when (code) {
            401, 403 -> "کلید Pollinations نامعتبر است یا اجازه این مدل را ندارد ($code)"
            402 -> "اعتبار رایگان هفتگی Pollinations تمام شده است"
            429 -> "تعداد درخواست زیاد است. چند دقیقه بعد دوباره امتحان کنید"
            else -> "خطا ($code): $snippet"
        })
    }
    return BitmapFactory.decodeByteArray(data, 0, data.size)
        ?: throw Exception("پاسخ عکس نبود: " + String(data).take(150))
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
