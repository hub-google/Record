package com.hubgoogle.record.core

import android.content.Context
import android.os.Build
import com.hubgoogle.record.data.RecordingFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * A self-attested, local acceptance journal. NEVER automatically marks a device
 * supported from merely capturing nonzero PCM or having permissions.
 */
data class CallTrial(
    val id: String,
    val capturedAtEpochMs: Long,
    val buildHash: String,
    val source: String,
    val direction: String,
    val route: String,
    val selfAudible: Boolean,
    val remoteAudible: Boolean,
    val durationMs: Long,
    val pcmSha256: String
) {
    val twoPartyPass: Boolean get() =
        source == "CALL_MIX" && selfAudible && remoteAudible && durationMs > 1000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("capturedAtEpochMs", capturedAtEpochMs)
        put("buildFingerprintHash", buildHash)
        put("source", source)
        put("direction", direction)
        put("route", route)
        put("selfAudible", selfAudible)
        put("remoteAudible", remoteAudible)
        put("durationMs", durationMs)
        put("pcmSha256", pcmSha256)
        put("outcome", if (twoPartyPass) "USER_REPORTED_BOTH" else "USER_REPORTED_PARTIAL")
        put("evidenceType", "USER_LISTENING_TO_LOCAL_RECORDING")
    }

    companion object {
        fun fromJson(o: JSONObject): CallTrial = CallTrial(
            o.getString("id"), o.getLong("capturedAtEpochMs"),
            o.getString("buildFingerprintHash"), o.getString("source"),
            o.getString("direction"), o.getString("route"),
            o.getBoolean("selfAudible"), o.getBoolean("remoteAudible"),
            o.getLong("durationMs"), o.getString("pcmSha256")
        )
    }
}

data class AcceptanceSummary(
    val attempts: Int,
    val bothAudible: Int,
    val incoming: Int,
    val outgoing: Int,
    val uniqueAudioEvidence: Int,
    val meetsSelfReportedBaseline: Boolean
)

object TrialEvaluator {
    fun evaluate(records: List<CallTrial>, build: String): AcceptanceSummary {
        val eligible = records.filter { it.buildHash == build && it.source == "CALL_MIX" }
            .distinctBy { it.pcmSha256 }
        val success = eligible.count { it.twoPartyPass }
        val incoming = eligible.count { it.direction == "INCOMING" }
        val outgoing = eligible.count { it.direction == "OUTGOING" }
        return AcceptanceSummary(
            eligible.size, success, incoming, outgoing, eligible.size,
            eligible.size >= 10 && success * 10 >= eligible.size * 9 &&
                incoming > 0 && outgoing > 0
        )
    }
}

class AcceptanceJournal(private val context: Context) {
    private val prefs = context.getSharedPreferences("record-call-trials", Context.MODE_PRIVATE)
    companion object {
        val DIRECTIONS = setOf("INCOMING", "OUTGOING")
        val ROUTES = setOf("EARPIECE", "SPEAKER", "BLUETOOTH", "OTHER")
        val SOURCES = setOf("CALL_MIX", "CALL_TX", "CALL_RX")
        fun deviceHash(fingerprint: String) =
            MessageDigest.getInstance("SHA-256")
                .digest(fingerprint.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)
    }
    val currentBuildHash: String get() = deviceHash(Build.FINGERPRINT)

    fun list(): List<CallTrial> {
        val saved = prefs.getString("trials", "[]") ?: "[]"
        val a = JSONArray(saved)
        return (0 until a.length()).map { CallTrial.fromJson(a.getJSONObject(it)) }
    }

    fun add(file: RecordingFile, direction: String, route: String,
            selfAudible: Boolean, remoteAudible: Boolean): CallTrial {
        val details = checkNotNull(file.details) { "No verified PCM metadata" }
        require(direction in DIRECTIONS && route in ROUTES)
        require(details.source in SOURCES) { "Not a cellular call source" }
        require(file.file.exists() && file.file.extension.equals("wav", true))
        require(details.status != "EMPTY" && details.status != "SILENT")
        require(Regex("[0-9a-f]{64}").matches(details.sha256)) {
            "Audio SHA-256 is required for an auditable test"
        }
        val old = list()
        check(old.none { it.pcmSha256 == details.sha256 }) {
            "This audio segment is already included in the trial journal"
        }
        val trial = CallTrial(
            UUID.randomUUID().toString(),
            System.currentTimeMillis(),
            currentBuildHash,
            details.source,
            direction, route, selfAudible, remoteAudible,
            details.durationMs,
            details.sha256
        )
        prefs.edit().putString("trials", JSONArray().apply {
            (old + trial).takeLast(500).forEach { put(it.toJson()) }
        }.toString()).apply()
        return trial
    }

    fun remove(id: String) {
        prefs.edit().putString("trials", JSONArray().apply {
            list().filterNot { it.id == id }.forEach { put(it.toJson()) }
        }.toString()).apply()
    }

    fun summary(): AcceptanceSummary = TrialEvaluator.evaluate(list(), currentBuildHash)

    fun exportJson(): JSONObject {
        val report = CompatibilityMatrix(context).toJson()
        report.put("callTrialJournalVersion", 1)
        report.put("trialSummary", JSONObject().apply {
            val summary = summary()
            put("uniqueAttempts", summary.attempts)
            put("twoPartyAudible", summary.bothAudible)
            put("incoming", summary.incoming)
            put("outgoing", summary.outgoing)
            put("selfReportedBaselineReached", summary.meetsSelfReportedBaseline)
            put("verifiedByIndependentTester", false)
        })
        report.put("callTrials", JSONArray().apply { list().forEach { put(it.toJson()) } })
        report.put("warning", "Manual playback confirmation is self-reported; independent device tests are not performed by CI.")
        return report
    }
}
