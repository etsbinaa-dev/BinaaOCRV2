package com.binaa.ocr.sync

import com.binaa.ocr.core.sync.RemoteFile
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

/**
 * مجلد التطبيق الخاص على Google Drive (appDataFolder): لا يظهر في Drive ولا يصل إليه أي تطبيق آخر. فيه ملفات مشفّرة فقط.
 * طلبات REST مباشرة (HTTPS) برمز الوصول؛ لا مكتبة Drive.
 */
class DriveClient(private val accessToken: String) {

    class HttpException(val code: Int) : IOException("Drive HTTP $code")

    fun list(): List<RemoteFile> {
        val out = mutableListOf<RemoteFile>()
        var page: String? = null
        do {
            val url = "$API/files?spaces=appDataFolder&pageSize=1000&fields=" + enc("nextPageToken,files(id,name,modifiedTime)") +
                (page?.let { "&pageToken=" + enc(it) } ?: "")
            val body = JSONObject(request("GET", url).toString(Charsets.UTF_8))
            val files = body.optJSONArray("files")
            for (i in 0 until (files?.length() ?: 0)) {
                val f = files!!.getJSONObject(i)
                out += RemoteFile(f.getString("id"), f.getString("name"), runCatching { Instant.parse(f.getString("modifiedTime")).toEpochMilli() }.getOrDefault(0))
            }
            page = body.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return out
    }

    fun download(fileId: String): ByteArray = request("GET", "$API/files/${enc(fileId)}?alt=media")

    fun upload(name: String, content: ByteArray) {
        val boundary = "binaa-" + UUID.randomUUID()
        val meta = JSONObject().put("name", name).put("parents", org.json.JSONArray().put("appDataFolder")).toString()
        val body = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
            "--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray() + content + "\r\n--$boundary--\r\n".toByteArray()
        request("POST", "$UPLOAD/files?uploadType=multipart&fields=id", body, "multipart/related; boundary=$boundary")
    }

    /** حذف ملف؛ ملف محذوف من قبل (404) لا يُعدّ خطأ. */
    fun delete(fileId: String) {
        try {
            request("DELETE", "$API/files/${enc(fileId)}")
        } catch (e: HttpException) {
            if (e.code != 404) throw e
        }
    }

    private fun request(method: String, url: String, body: ByteArray? = null, contentType: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code !in 200..299) throw HttpException(code)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
