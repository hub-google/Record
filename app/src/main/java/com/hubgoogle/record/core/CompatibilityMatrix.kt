package com.hubgoogle.record.core

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.content.pm.PackageManager
import android.Manifest
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Distinguish a permission/API capability from verified real-device results.
 * Only the person performing playback validation can set a test status, and
 * statuses are explicitly tagged "USER_REPORTED", never automatic PASS.
 */
class CompatibilityMatrix(private val context: Context) {
    enum class Outcome { NOT_TESTED, PASS, PARTIAL, FAIL }
    companion object {
        val FEATURES = listOf(
            "CALL_MIX", "CALL_TX", "CALL_RX", "MEDIA", "CALL_AND_MEDIA",
            "BLUETOOTH", "SPEAKER", "VOLTE", "VOWIFI", "VOIP"
        )
        fun statusName(raw: String?): Outcome =
            Outcome.entries.firstOrNull { it.name == raw } ?: Outcome.NOT_TESTED
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(24)
    }

    private val prefs = context.getSharedPreferences("compatibility", Context.MODE_PRIVATE)
    fun result(feature: String): Outcome {
        require(feature in FEATURES)
        return statusName(prefs.getString("result.$feature", null))
    }
    fun record(feature: String, outcome: Outcome) {
        require(feature in FEATURES)
        prefs.edit()
            .putString("result.$feature", outcome.name)
            .putLong("updated.$feature", System.currentTimeMillis())
            .apply()
    }

    fun toJson(): JSONObject {
        val pm = context.getSystemService(PowerManager::class.java)
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        fun encoder(mime: String) = codecs.any {
            it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        val fingerprint = hash(Build.FINGERPRINT)
        val report = JSONObject().apply {
            put("schema", "record.compatibility/1")
            put("generatedAtEpochMs", System.currentTimeMillis())
            put("privacy", "NO_PHONE_NUMBER_NO_AUDIO_NO_ADB_TOKENS")
            put("device", JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("androidSdk", Build.VERSION.SDK_INT)
                put("androidRelease", Build.VERSION.RELEASE)
                put("securityPatch", Build.VERSION.SECURITY_PATCH)
                put("buildFingerprintHashPrefix", fingerprint)
            })
            put("environment", JSONObject().apply {
                put("recordAudioGranted",
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                put("phoneStateGranted",
                    context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
                put("batteryOptimizationIgnored", pm.isIgnoringBatteryOptimizations(context.packageName))
                put("availableStorageMiB", context.filesDir.usableSpace / 1048576L)
                put("flacEncoderListed", encoder(MediaFormat.MIMETYPE_AUDIO_FLAC))
                put("aacEncoderListed", encoder(MediaFormat.MIMETYPE_AUDIO_AAC))
                put("wirelessDebuggingEnabled", try {
                    Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
                } catch (ignored: SecurityException) { JSONObject.NULL })
            })
            put("tests", JSONArray().apply {
                for (feature in FEATURES) put(JSONObject().apply {
                    put("feature", feature)
                    put("outcome", result(feature).name)
                    put("evidenceType", if (result(feature) == Outcome.NOT_TESTED) "NONE" else "USER_REPORTED")
                    put("recordedAtEpochMs", prefs.getLong("updated.$feature", 0L))
                })
            })
            put("warning",
                "A codec listed or audio source initialized does not prove both parties recorded. " +
                "PASS denotes user report, not independently verified device validation.")
        }
        return report
    }
}

