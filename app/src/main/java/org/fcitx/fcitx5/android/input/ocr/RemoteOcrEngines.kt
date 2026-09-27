/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OCRSCAN: cloud / self-hosted OCR backends.
 *
 * All of them share [HttpOcrEngine], which only needs an endpoint, headers and a
 * body; the differences between providers are authentication and response layout.
 * A fully user-defined backend ([CustomHttpProvider]) covers any other service.
 */
private const val TIMEOUT_MS = 20_000

private fun Bitmap.toJpegBase64(quality: Int = 90): String {
    val buf = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, buf)
    return Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
}

private fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

private fun hmacSha256(key: ByteArray, data: String): ByteArray =
    Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

private fun hmacHex(key: ByteArray, data: String): String =
    hmacSha256(key, data).joinToString("") { "%02x".format(it) }

/** Walks a dotted path such as `words_result[].words` and collects the text. */
private fun extractByPath(body: String, path: String): String {
    if (path.isBlank()) {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed
        // no path given: try the layouts we know about
        listOf("words_result[].words", "TextDetections[].DetectedText", "result[].text", "text")
            .forEach { p ->
                val v = runCatching { extractByPath(body, p) }.getOrNull()
                if (!v.isNullOrBlank()) return v
            }
        return ""
    }
    val root = JSONObject(body)
    var current: Any? = root
    for (segment in path.split('.')) {
        current ?: return ""
        if (segment.endsWith("[]")) {
            val key = segment.removeSuffix("[]")
            val arr = when (current) {
                is JSONObject -> current.optJSONArray(key) ?: return ""
                is JSONArray -> current
                else -> return ""
            }
            val rest = path.substringAfter(segment + ".", "")
            val parts = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val item = arr.opt(i) ?: continue
                val value = if (rest.isEmpty()) item else {
                    val sub = runCatching { extractFrom(item, rest) }.getOrNull()
                    sub
                }
                if (value is String) parts.add(value)
            }
            return parts.joinToString("\n")
        } else {
            current = when (current) {
                is JSONObject -> (current as JSONObject).opt(segment)
                is JSONArray -> (current as JSONArray).opt(segment.toIntOrNull() ?: return "")
                else -> return ""
            }
        }
    }
    return current?.toString() ?: ""
}

private fun extractFrom(node: Any, path: String): Any? {
    var cur: Any? = node
    for (segment in path.split('.')) {
        cur ?: return null
        cur = when (cur) {
            is JSONObject -> cur.opt(segment)
            is JSONArray -> cur.opt(segment.toIntOrNull() ?: return null)
            else -> return null
        }
    }
    return cur
}

private fun httpPost(
    url: String,
    headers: Map<String, String>,
    body: ByteArray,
    contentType: String
): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        setRequestProperty("Content-Type", contentType)
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    return try {
        DataOutputStream(conn.outputStream).use { it.write(body) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(200)}")
        text
    } finally {
        conn.disconnect()
    }
}

private fun httpGet(url: String): String {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
    }
    return try {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${text.take(200)}")
        text
    } finally {
        conn.disconnect()
    }
}

/**
 * Shared HTTP plumbing: builds the request body from the bitmap and delegates
 * parsing to the subclass.
 */
private abstract class HttpOcrEngine : OcrEngine {

    protected abstract fun endpoint(): String
    protected abstract fun headers(): Map<String, String>
    protected abstract fun contentType(): String
    protected abstract fun body(bitmap: Bitmap): ByteArray
    protected abstract fun parse(response: String): String

    override fun recognize(bitmap: Bitmap): OcrResult = try {
        val text = parse(httpPost(endpoint(), headers(), body(bitmap), contentType()))
        OcrResult(text.trim())
    } catch (e: Throwable) {
        Timber.e(e, "OcrScan: remote recognition failed (${id})")
        OcrResult("", error = e.message)
    }

    override fun prepare(context: Context): Boolean = true
    override fun close() {}
}

/* ============================ Baidu ============================ */

private class BaiduOcrEngine(private val config: OcrConfig) : HttpOcrEngine() {

    override val id: String get() = BaiduProvider.ID
    override val displayName: String get() = "百度 OCR"

    @Volatile
    private var token: String? = null

    private fun token(): String {
        token?.let { return it }
        val ak = config.get(KEY_API_KEY)
        val sk = config.get(KEY_SECRET_KEY)
        val url = "https://aip.baidubce.com/oauth/2.0/token?grant_type=client_credentials" +
            "&client_id=${URLEncoder.encode(ak, "UTF-8")}" +
            "&client_secret=${URLEncoder.encode(sk, "UTF-8")}"
        val t = JSONObject(httpGet(url)).optString("access_token")
        if (t.isNullOrEmpty()) throw RuntimeException("获取 access_token 失败")
        token = t
        return t
    }

    override fun endpoint(): String {
        val path = config.get(KEY_PATH, "accurate_basic")
        return "https://aip.baidubce.com/rest/2.0/ocr/v1/$path?access_token=${token()}"
    }

    override fun contentType(): String = "application/x-www-form-urlencoded"

    override fun headers(): Map<String, String> = emptyMap()

    override fun body(bitmap: Bitmap): ByteArray {
        val image = URLEncoder.encode(bitmap.toJpegBase64(), "UTF-8")
        return "image=$image".toByteArray()
    }

    override fun parse(response: String): String =
        extractByPath(response, "words_result[].words")

    override fun prepare(context: Context): Boolean =
        config.get(KEY_API_KEY).isNotEmpty() && config.get(KEY_SECRET_KEY).isNotEmpty()

    companion object {
        const val KEY_API_KEY = "apiKey"
        const val KEY_SECRET_KEY = "secretKey"
        const val KEY_PATH = "path"
    }
}

object BaiduProvider : OcrEngineProvider {
    const val ID = "baidu"
    override val id: String get() = ID
    override val displayName: String get() = "百度智能云 OCR"
    override fun create(context: Context, config: OcrConfig): OcrEngine = BaiduOcrEngine(config)

    override val spec = OcrEngineSpec(
        id = ID,
        displayName = "百度智能云 OCR",
        description = "高精度通用文字识别，云端，每月有免费额度",
        local = false,
        fields = listOf(
            OcrFieldSpec(BaiduOcrEngine.KEY_API_KEY, "API Key"),
            OcrFieldSpec(BaiduOcrEngine.KEY_SECRET_KEY, "Secret Key", secret = true),
            OcrFieldSpec(BaiduOcrEngine.KEY_PATH, "接口路径", defaultValue = "accurate_basic",
                hint = "accurate_basic / general_basic / accurate 等")
        )
    )
}

/* ============================ Tencent ============================ */

private class TencentOcrEngine(private val config: OcrConfig) : HttpOcrEngine() {

    override val id: String get() = TencentProvider.ID
    override val displayName: String get() = "腾讯云 OCR"

    private val host = "ocr.tencentcloudapi.com"

    override fun endpoint(): String = "https://$host/"

    override fun contentType(): String = "application/json; charset=utf-8"

    override fun headers(): Map<String, String> {
        val action = config.get(KEY_ACTION, "GeneralAccurateOCR")
        val region = config.get(KEY_REGION, "ap-guangzhou")
        val secretId = config.get(KEY_SECRET_ID)
        val secretKey = config.get(KEY_SECRET_KEY)
        val payload = payloadCache ?: ""
        val timestamp = (System.currentTimeMillis() / 1000L)
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val date = sdf.format(Date(timestamp * 1000))
        val canonical = buildString {
            append("POST\n/\n\ncontent-type:${contentType()}\nhost:$host\n\n")
            append("content-type;host\n")
            append(sha256Hex(payload))
        }
        val stringToSign = "TC3-HMAC-SHA256\n$timestamp\n$date/ocr/tc3_request\n${sha256Hex(canonical)}"
        val secretDate = hmacSha256(("TC3$secretKey").toByteArray(), date)
        val secretService = hmacSha256(secretDate, "ocr")
        val secretSigning = hmacSha256(secretService, "tc3_request")
        val signature = hmacHex(secretSigning, stringToSign)
        val auth = "TC3-HMAC-SHA256 Credential=$secretId/$date/ocr/tc3_request, " +
            "SignedHeaders=content-type;host, Signature=$signature"
        return mapOf(
            "Authorization" to auth,
            "Host" to host,
            "X-TC-Action" to action,
            "X-TC-Version" to "2018-11-19",
            "X-TC-Timestamp" to timestamp.toString(),
            "X-TC-Region" to region
        )
    }

    @Volatile
    private var payloadCache: String? = null

    override fun body(bitmap: Bitmap): ByteArray {
        val payload = JSONObject().apply {
            put("ImageBase64", bitmap.toJpegBase64())
        }.toString()
        payloadCache = payload
        return payload.toByteArray()
    }

    override fun parse(response: String): String =
        extractByPath(response, "Response.TextDetections[].DetectedText")

    override fun prepare(context: Context): Boolean =
        config.get(KEY_SECRET_ID).isNotEmpty() && config.get(KEY_SECRET_KEY).isNotEmpty()

    companion object {
        const val KEY_SECRET_ID = "secretId"
        const val KEY_SECRET_KEY = "secretKey"
        const val KEY_REGION = "region"
        const val KEY_ACTION = "action"
    }
}

object TencentProvider : OcrEngineProvider {
    const val ID = "tencent"
    override val id: String get() = ID
    override val displayName: String get() = "腾讯云 OCR"
    override fun create(context: Context, config: OcrConfig): OcrEngine = TencentOcrEngine(config)

    override val spec = OcrEngineSpec(
        id = ID,
        displayName = "腾讯云 OCR",
        description = "通用高精度版，云端，TC3 签名",
        local = false,
        fields = listOf(
            OcrFieldSpec(TencentOcrEngine.KEY_SECRET_ID, "SecretId"),
            OcrFieldSpec(TencentOcrEngine.KEY_SECRET_KEY, "SecretKey", secret = true),
            OcrFieldSpec(TencentOcrEngine.KEY_REGION, "地域", defaultValue = "ap-guangzhou"),
            OcrFieldSpec(TencentOcrEngine.KEY_ACTION, "接口 Action", defaultValue = "GeneralAccurateOCR")
        )
    )
}

/* ====================== 白描桌面版 ====================== */

/**
 * 白描桌面版（Tauri）的「本地服务器模式」：
 * 官方文档 https://github.com/baimiaoapp/baimiao-desktop/blob/main/API.md
 *
 * - 设置页里可配置监听 IP 与端口，默认 `0.0.0.0:8888`（因此端口完全由用户决定）
 * - `POST /ocr`，`multipart/form-data`，图片字段名为 `image`（或 base64 字段 `b64`）
 * - **没有鉴权**，Token 字段只为自建反代预留，留空就不发 Authorization 头
 * - 响应是 JSON，`data.text_all` 是拼接好的全文；失败时 `code != 1`
 */
private class BaimiaoOcrEngine(private val config: OcrConfig) : HttpOcrEngine() {

    override val id: String get() = BaimiaoProvider.ID
    override val displayName: String get() = "白描"

    override fun endpoint(): String = config.get(KEY_BASE_URL).trimEnd('/') + "/ocr"

    override fun contentType(): String = "multipart/form-data; boundary=$BOUNDARY"

    override fun headers(): Map<String, String> {
        val token = config.get(KEY_TOKEN)
        return if (token.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $token")
    }

    override fun body(bitmap: Bitmap): ByteArray {
        val buf = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, buf)
        val image = buf.toByteArray()
        val out = ByteArrayOutputStream()
        val lang = config.get(KEY_LANG)
        if (lang.isNotEmpty()) {
            out.write(formField(KEY_LANG, lang))
        }
        out.write("--$BOUNDARY\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"image\"; filename=\"ocr.jpg\"\r\n".toByteArray())
        out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
        out.write(image)
        out.write("\r\n--$BOUNDARY--\r\n".toByteArray())
        return out.toByteArray()
    }

    private fun formField(name: String, value: String) =
        "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray()

    override fun parse(response: String): String {
        val root = JSONObject(response)
        if (root.optInt("code", 0) != 1) {
            throw RuntimeException(root.optString("msg", "白描返回失败"))
        }
        val data = root.optJSONObject("data") ?: return ""
        data.optString("text_all").takeIf { it.isNotEmpty() }?.let { return it }
        // Older builds may not send text_all; join the lines the way the official
        // sample does: no space between CJK lines, single space otherwise.
        val lines = data.optJSONArray("lines") ?: return ""
        val sb = StringBuilder()
        var lastLineFeed = true
        for (i in 0 until lines.length()) {
            val line = lines.optJSONObject(i) ?: continue
            val lineFeed = line.optBoolean("lineFeed", false)
            if (!lastLineFeed && line.optString("lang") !in CJK_LANGS) {
                sb.append(' ')
            }
            sb.append(line.optString("text").orEmpty())
            if (lineFeed) sb.append('\n')
            lastLineFeed = lineFeed
        }
        return sb.toString()
    }

    override fun prepare(context: Context): Boolean = config.get(KEY_BASE_URL).isNotEmpty()

    companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_LANG = "lang"
        const val KEY_TOKEN = "token"
        private const val BOUNDARY = "----fcitx5ocrBoundary"
        /** whatlang 里不需要空格分隔的语种：中文 / 日文 / 韩文 */
        private val CJK_LANGS = setOf("cmn", "jpn", "kor")
    }
}

object BaimiaoProvider : OcrEngineProvider {
    const val ID = "baimiao"
    override val id: String get() = ID
    override val displayName: String get() = "白描（桌面版）"
    override fun create(context: Context, config: OcrConfig): OcrEngine = BaimiaoOcrEngine(config)

    override val spec = OcrEngineSpec(
        id = ID,
        displayName = "白描（桌面版）",
        description = "局域网内白描桌面版「本地服务器模式」，无需鉴权",
        local = false,
        fields = listOf(
            OcrFieldSpec(
                BaimiaoOcrEngine.KEY_BASE_URL, "服务地址",
                hint = "http://192.168.1.10:51314（默认端口 8888，以客户端设置为准）"
            ),
            OcrFieldSpec(
                BaimiaoOcrEngine.KEY_LANG, "识别语言",
                hint = "留空为默认中英文；如 zh-Hans,en-US（中文只能与英文组合）"
            ),
            OcrFieldSpec(
                BaimiaoOcrEngine.KEY_TOKEN, "Token（可选）", secret = true,
                hint = "官方本地服务无需鉴权；仅自建反代需要时填写"
            )
        )
    )
}

/* ============= 白描手机版「WiFi 传输识别」 ============= */

/**
 * 白描（Android）自带的「WiFi 传输识别」局域网服务：
 * 白描首页右上角 WIFI 按钮开启后，App 内起一个 HTTP 服务（如
 * `http://192.168.3.5:51314`），浏览器打开该地址即可上传图片识别。
 * 这里把官方 Web UI（`transfer.js` / `result.js`）用到的端点原样复刻，
 * 不依赖任何私有或未公开的协议。
 *
 * 端点（**无鉴权**，均为明文 HTTP）：
 * - `POST /files`          multipart：`fileName=<文件名>` + `newfile=<图片>`
 *                          同名上传会覆盖（但不会重置识别状态），列表上限 50 张
 * - `GET  /files?<ts>`     → `{recognizeStatus, files:[{path,name,width,height,status,size}]}`
 *                          `status`：0 未识别 / 1 识别中 / 2 已识别 / 3 失败
 * - `POST /recognize/all`  `_method=recognize`，批量识别列表里所有图片
 * - `POST /filesResult`    → `{recognizeResult, files:[{... ,result}]}`
 * - `POST /fileAllDelete`  `_method=delete`，清空列表
 *
 * 已知限制：
 * - 官方说明要求白描保持在前台；后台时服务可能被系统回收，此时会报连接失败。
 * - `fileDelete`（单张删除）在实测版本里是空操作，所以清理只能整表清。
 * - 服务端会把同一张图里的多个文本块直接拼接，块之间不一定有换行。
 * - **同名覆盖不会把 `status` 重置为 0**：若沿用固定文件名，第二次识别会立刻读到
 *   上一张图的旧结果。因此每次识别都用 `前缀-<时间戳>.jpg` 这样的唯一名字，
 *   并在自己堆积到 [AUTO_CLEANUP_THRESHOLD] 张时整表清理（列表上限 50 张）。
 * - **扩展名必须与图片内容一致**：实测把 PNG 内容命名成 `.jpg` 上传，服务端会一直
 *   停在 `status=0` 不识别。这里固定压缩成 JPEG 并用 `.jpg` 后缀。
 */
private class BaimiaoWifiOcrEngine(private val config: OcrConfig) : OcrEngine {

    override val id: String get() = BaimiaoWifiProvider.ID
    override val displayName: String get() = "白描（手机 WiFi）"

    override fun prepare(context: Context): Boolean = config.get(KEY_BASE_URL).isNotEmpty()

    override fun close() {}

    override fun recognize(bitmap: Bitmap): OcrResult = try {
        val base = config.get(KEY_BASE_URL).trimEnd('/')
        val prefix = config.get(KEY_FILE_NAME).ifEmpty { DEFAULT_FILE_PREFIX }
        // 唯一文件名：同名覆盖不会重置 status，复用名字会读到上一次的旧结果
        val name = "$prefix-${System.currentTimeMillis()}.jpg"
        waitIdle(base)
        upload(base, name, bitmap)
        if (!awaitUploaded(base, name)) throw RuntimeException("白描没有接收上传的图片")
        formPost("$base/recognize/all", KEY_METHOD to "recognize")
        val text = awaitResult(base, name)
        cleanup(base, prefix)
        OcrResult(text.trim())
    } catch (e: Throwable) {
        Timber.e(e, "OcrScan: baimiao wifi recognition failed")
        OcrResult("", error = e.message)
    }

    private fun upload(base: String, name: String, bitmap: Bitmap) {
        val jpeg = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, this)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("--$BOUNDARY\r\n".toByteArray())
        out.write("Content-Disposition: form-data; name=\"fileName\"\r\n\r\n$name\r\n".toByteArray())
        out.write("--$BOUNDARY\r\n".toByteArray())
        out.write(
            "Content-Disposition: form-data; name=\"newfile\"; filename=\"$name\"\r\n".toByteArray()
        )
        out.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
        out.write(jpeg)
        out.write("\r\n--$BOUNDARY--\r\n".toByteArray())
        httpPost("$base/files", emptyMap(), out.toByteArray(), "multipart/form-data; boundary=$BOUNDARY")
    }

    /** 上一批还在识别时官方 UI 会禁止上传，这里同样等它空闲。 */
    private fun waitIdle(base: String) {
        repeat(10) {
            val status = runCatching {
                parseObject(httpGet("$base/files?${System.currentTimeMillis()}"))
                    ?.optInt("recognizeStatus", 0)
            }.getOrNull() ?: return
            if (status != 1) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
    }

    /** 上传是异步落盘的，先等这张图出现在列表里再触发识别。 */
    private fun awaitUploaded(base: String, name: String): Boolean {
        val deadline = System.currentTimeMillis() + UPLOAD_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching {
                parseObject(httpGet("$base/files?${System.currentTimeMillis()}"))?.let { findFile(it, name) }
            }.getOrNull()
            if (found != null) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    /** 轮询文件列表，直到我们这张图识别完成。 */
    private fun awaitResult(base: String, name: String): String {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        var lastStatus = -1
        while (System.currentTimeMillis() < deadline) {
            val entry = runCatching {
                parseObject(httpGet("$base/files?${System.currentTimeMillis()}"))?.let { findFile(it, name) }
            }.getOrNull()
            lastStatus = entry?.optInt("status", -1) ?: lastStatus
            when (lastStatus) {
                STATUS_DONE -> return fetchResult(base, name)
                STATUS_FAILED -> throw RuntimeException("白描识别失败")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw RuntimeException("等待白描识别结果超时，请确认白描已打开 WiFi 传输并处于前台")
    }

    /** 官方 UI 用 `filesResult` 取全文；单图端点 `fileResult` 实测返回空。 */
    private fun fetchResult(base: String, name: String): String {
        var body = ""
        repeat(3) {
            body = formPost("$base/filesResult")
            val text = parseObject(body)?.let { findFile(it, name) }?.optString("result").orEmpty()
            if (text.isNotEmpty()) return text
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return parseObject(body)?.optString("recognizeResult").orEmpty()
    }

    /**
     * 官方的单张删除 `fileDelete` 实测无效，只能整表清；默认只在自己堆积到阈值时
     * 才清，避免误删用户自己通过网页传的图。
     */
    private fun cleanup(base: String, prefix: String) {
        if (config.get(KEY_CLEANUP) != "true") {
            val mine = runCatching {
                val arr = parseObject(httpGet("$base/files?${System.currentTimeMillis()}"))
                    ?.optJSONArray("files") ?: return@runCatching 0
                (0 until arr.length()).count {
                    arr.optJSONObject(it)?.optString("name")?.startsWith(prefix) == true
                }
            }.getOrDefault(0)
            if (mine < AUTO_CLEANUP_THRESHOLD) return
        }
        runCatching { formPost("$base/fileAllDelete", KEY_METHOD to "delete") }
            .onFailure { Timber.w(it, "OcrScan: baimiao wifi cleanup failed") }
    }

    private fun findFile(root: JSONObject, name: String): JSONObject? {
        val arr = root.optJSONArray("files") ?: return null
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("name") == name) return item
        }
        return null
    }

    /** 列表为空时服务端直接返回 `[]`，不是对象，这里统一兜住。 */
    private fun parseObject(body: String): JSONObject? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }

    private fun formPost(url: String, vararg fields: Pair<String, String>): String {
        val body = fields.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.toByteArray()
        return httpPost(url, emptyMap(), body, "application/x-www-form-urlencoded")
    }

    companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_CLEANUP = "cleanup"
        private const val KEY_METHOD = "_method"
        const val DEFAULT_FILE_PREFIX = "fcitx5-ocr"
        private const val BOUNDARY = "----fcitx5ocrWifiBoundary"
        private const val STATUS_DONE = 2
        private const val STATUS_FAILED = 3
        private const val POLL_INTERVAL_MS = 400L
        private const val POLL_TIMEOUT_MS = 20_000L
        private const val UPLOAD_GRACE_MS = 4_000L
        /** 自己堆积到这么多张就整表清理，服务端上限 50 张 */
        private const val AUTO_CLEANUP_THRESHOLD = 12
    }
}

object BaimiaoWifiProvider : OcrEngineProvider {
    const val ID = "baimiao-wifi"
    override val id: String get() = ID
    override val displayName: String get() = "白描（手机 WiFi）"
    override fun create(context: Context, config: OcrConfig): OcrEngine = BaimiaoWifiOcrEngine(config)

    override val spec = OcrEngineSpec(
        id = ID,
        displayName = "白描（手机 WiFi）",
        description = "局域网内白描手机端「WiFi 传输识别」，无需鉴权，需白描在前台",
        local = false,
        fields = listOf(
            OcrFieldSpec(
                BaimiaoWifiOcrEngine.KEY_BASE_URL, "服务地址",
                hint = "白描 WiFi 传输页面显示的地址，如 http://192.168.3.5:51314"
            ),
            OcrFieldSpec(
                BaimiaoWifiOcrEngine.KEY_FILE_NAME, "文件名前缀",
                defaultValue = BaimiaoWifiOcrEngine.DEFAULT_FILE_PREFIX,
                hint = "实际上传名为「前缀-时间戳.jpg」，必须唯一，否则会读到旧结果"
            ),
            OcrFieldSpec(
                BaimiaoWifiOcrEngine.KEY_CLEANUP, "每次识别后清空列表",
                hint = "填 true 开启：每次都调用 fileAllDelete（会删掉你自己传的图）。默认关闭，仅在自己堆积到 12 张时才清"
            )
        )
    )
}

/* ============================ Custom ============================ */

private class CustomHttpOcrEngine(private val config: OcrConfig) : HttpOcrEngine() {

    override val id: String get() = CustomHttpProvider.ID
    override val displayName: String get() = "自定义接口"

    override fun endpoint(): String = config.get(KEY_URL)

    override fun contentType(): String = config.get(KEY_CONTENT_TYPE, "application/json")

    override fun headers(): Map<String, String> =
        config.get(KEY_HEADERS).lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else
                    line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }.toMap()

    override fun body(bitmap: Bitmap): ByteArray {
        val template = config.get(KEY_BODY, "{\"image\":\"{base64}\"}")
        return template.replace("{base64}", bitmap.toJpegBase64()).toByteArray()
    }

    override fun parse(response: String): String = extractByPath(response, config.get(KEY_PATH))

    override fun prepare(context: Context): Boolean = config.get(KEY_URL).isNotEmpty()

    companion object {
        const val KEY_URL = "url"
        const val KEY_HEADERS = "headers"
        const val KEY_BODY = "body"
        const val KEY_PATH = "path"
        const val KEY_CONTENT_TYPE = "contentType"
    }
}

object CustomHttpProvider : OcrEngineProvider {
    const val ID = "custom"
    override val id: String get() = ID
    override val displayName: String get() = "自定义 HTTP 接口"
    override fun create(context: Context, config: OcrConfig): OcrEngine = CustomHttpOcrEngine(config)

    override val spec = OcrEngineSpec(
        id = ID,
        displayName = "自定义 HTTP 接口",
        description = "任意服务商 / 自建接口，自行定义请求体与结果路径",
        local = false,
        fields = listOf(
            OcrFieldSpec(CustomHttpOcrEngine.KEY_URL, "接口 URL", hint = "https://..."),
            OcrFieldSpec(CustomHttpOcrEngine.KEY_HEADERS, "请求头",
                hint = "每行一条，如：Authorization: Bearer xxx"),
            OcrFieldSpec(CustomHttpOcrEngine.KEY_BODY, "请求体模板",
                defaultValue = "{\"image\":\"{base64}\"}", hint = "{base64} 会替换为图片"),
            OcrFieldSpec(CustomHttpOcrEngine.KEY_PATH, "结果 JSON 路径",
                hint = "如 words_result[].words，留空自动猜测"),
            OcrFieldSpec(CustomHttpOcrEngine.KEY_CONTENT_TYPE, "Content-Type",
                defaultValue = "application/json")
        )
    )
}
