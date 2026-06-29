package com.easybc.planner.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

open class GoogleDriveSyncClient {
    open suspend fun findSnapshot(accessToken: String): DriveSnapshot? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("name = '$SYNC_FILE_NAME' and trashed = false", Charsets.UTF_8.name())
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?spaces=appDataFolder&q=$query&fields=files(id,name,modifiedTime)&pageSize=1"
        val listed = request(url, accessToken)
        val root = SyncCrypto.json.parseToJsonElement(listed).jsonObject
        val fileId = root["files"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("id")?.jsonPrimitive?.content ?: return@withContext null
        val body = request(
            "https://www.googleapis.com/drive/v3/files/${encode(fileId)}?alt=media",
            accessToken,
        )
        DriveSnapshot(fileId, SyncCrypto.parseEnvelope(body))
    }

    open suspend fun writeSnapshot(
        accessToken: String,
        envelope: SyncEnvelopeV1,
        fileId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val content = SyncCrypto.json.encodeToString(envelope)
        if (fileId != null) {
            request(
                "https://www.googleapis.com/upload/drive/v3/files/${encode(fileId)}?uploadType=media&fields=id",
                accessToken,
                method = "PATCH",
                contentType = "application/json",
                body = content.toByteArray(Charsets.UTF_8),
            )
            return@withContext fileId
        }

        val boundary = "easybc-${UUID.randomUUID()}"
        val metadata = SyncCrypto.json.encodeToString<JsonObject>(
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(SYNC_FILE_NAME))
                put("parents", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive("appDataFolder"))
                })
            }
        )
        val multipart = buildString {
            append("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata)
            append("\r\n--$boundary\r\nContent-Type: application/json\r\n\r\n")
            append(content)
            append("\r\n--$boundary--")
        }.toByteArray(Charsets.UTF_8)
        val response = request(
            "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
            accessToken,
            method = "POST",
            contentType = "multipart/related; boundary=$boundary",
            body = multipart,
        )
        SyncCrypto.json.parseToJsonElement(response).jsonObject["id"]?.jsonPrimitive?.content
            ?: error("Google Drive did not return a file id.")
    }

    open suspend fun deleteSnapshot(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        request(
            "https://www.googleapis.com/drive/v3/files/${encode(fileId)}",
            accessToken,
            method = "DELETE",
        )
    }

    private fun request(
        url: String,
        accessToken: String,
        method: String = "GET",
        contentType: String? = null,
        body: ByteArray? = null,
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException(
                    "Google Drive request failed ($code). ${response.take(400)}"
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

