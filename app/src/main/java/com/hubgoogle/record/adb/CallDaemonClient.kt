package com.hubgoogle.record.adb

import android.content.Context
import com.hubgoogle.record.audio.WavPcmFile
import com.hubgoogle.record.data.Recordings
import com.hubgoogle.record.data.RecordingDatabase
import com.hubgoogle.record.core.QualityAccumulator
import android.os.SystemClock
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

class CallDaemonClient(private val context: Context) {
    companion object { const val PORT = 39731 }
    private val prefs = context.getSharedPreferences("daemon_auth", Context.MODE_PRIVATE)
    fun token(): String {
        val previous = prefs.getString("token", null)
        if (!previous.isNullOrEmpty()) return previous
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val created = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        prefs.edit().putString("token", created).apply()
        return created
    }
    private fun open(): Socket = Socket().apply {
        connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 2000)
        soTimeout = 15_000
    }
    private fun readLine(input: BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 1024) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("Unexpected socket close")
            if (b == 10) return bytes.toString("UTF-8").trimEnd('\r')
            bytes.write(b)
        }
        throw java.io.IOException("Oversized protocol response")
    }
    private fun command(name: String): String = open().use { socket ->
        val output = BufferedOutputStream(socket.getOutputStream())
        val input = BufferedInputStream(socket.getInputStream())
        output.write(("AUTH " + token() + "\n" + name + "\n").toByteArray())
        output.flush()
        val status = readLine(input)
        check(status == "OK") { "Daemon authentication failed: " + status }
        readLine(input)
    }
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) { runCatching { command("STATUS").startsWith("STATE ") }.getOrDefault(false) }

    suspend fun status(): String = withContext(Dispatchers.IO) { command("STATUS") }
    suspend fun selectSource(name: String): String = withContext(Dispatchers.IO) {
        require(name in setOf("CALL_MIX", "CALL_TX", "CALL_RX"))
        val answer = command("SELECT " + name)
        check(answer == "SELECTED " + name) { answer }
        answer
    }
    suspend fun pending(): List<String> = withContext(Dispatchers.IO) {
        val answer = command("PENDING")
        check(answer.startsWith("PENDING")) { answer }
        answer.removePrefix("PENDING").trim().split(' ').filter {
            Regex("[0-9a-fA-F-]{36}").matches(it)
        }
    }
    suspend fun start(): String = withContext(Dispatchers.IO) { command("START") }
    suspend fun stop(): String = withContext(Dispatchers.IO) { command("STOP") }
    suspend fun shutdown(): String = withContext(Dispatchers.IO) { command("SHUTDOWN") }

    /** Copies exactly the daemon's raw 16-bit PCM output, then atomically finalizes a WAV. */
    suspend fun fetch(sessionId: String): java.io.File = withContext(Dispatchers.IO) {
        require(Regex("[0-9a-fA-F-]{36}").matches(sessionId))
        val file = Recordings(context).sessionFile("call", sessionId)
        val quality = QualityAccumulator()
        val digest = MessageDigest.getInstance("SHA-256")
        var actualRate = 0
        var actualChannels = 0
        var sourceName = "CALL_MIX"
        var sourceStartNs = 0L
        var expectedHash = ""
        open().use { socket ->
            socket.soTimeout = 30_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            output.write(("AUTH " + token() + "\nFETCH " + sessionId + "\n").toByteArray())
            output.flush()
            check(readLine(input) == "OK") { "Authentication failed" }
            val info = readLine(input).split(" ")
            check(info.size == 7 && info[0] == "FILE") { "Unsupported daemon protocol: " + info.joinToString(" ") }
            val count = info[1].toLong()
            val rate = info[2].toInt()
            val channels = info[3].toInt()
            actualRate = rate
            actualChannels = channels
            sourceStartNs = info[4].toLong()
            sourceName = info[5]
            expectedHash = info[6].lowercase()
            check(Regex("[a-f0-9]{64}").matches(expectedHash)) { "Invalid SHA-256" }
            check(sourceName in setOf("CALL_MIX", "CALL_TX", "CALL_RX")) { "Unknown audio source" }
            check(count in 2L..512_000_000L && count % 2 == 0L) { "Empty, invalid or oversized PCM segment" }
            if (file.exists()) {
                check(file.length() == count + 44) { "Existing recovered file size mismatch" }
                val previousHash = MessageDigest.getInstance("SHA-256")
                val existingQuality = QualityAccumulator()
                file.inputStream().use { stream ->
                    check(stream.skip(44L) == 44L)
                    val existingBuffer = ByteArray(32768)
                    while (true) {
                        val got = stream.read(existingBuffer)
                        if (got < 0) break
                        previousHash.update(existingBuffer, 0, got)
                        existingQuality.add(existingBuffer, got)
                    }
                }
                val existingDigest = previousHash.digest().joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
                check(existingDigest == expectedHash) { "Existing file checksum differs from daemon" }
                // Drain this socket's already-started FETCH body before issuing
                // DELETE: the daemon serves one command at a time.
                val discard = ByteArray(32768)
                var bytesLeft = count
                while (bytesLeft > 0) {
                    val n = input.read(discard, 0, minOf(bytesLeft, discard.size.toLong()).toInt())
                    if (n < 0) throw java.io.EOFException("Truncated redundant transfer")
                    bytesLeft -= n
                }
                // Complete metadata repair if the prior attempt crashed after commit.
                RecordingDatabase(context).save(file, sourceName, rate, channels,
                    existingQuality.result(), expectedHash, sourceStartNs)
                check(command("DELETE " + sessionId) == "DELETED")
                return@withContext file
            }
            WavPcmFile(file, rate, channels).use { wav ->
                var remaining = count
                val buffer = ByteArray(32768)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read < 0) throw java.io.EOFException("Segment truncated")
                    wav.append(buffer, 0, read)
                    quality.add(buffer, read)
                    digest.update(buffer, 0, read)
                    remaining -= read
                }
                val actualHash = digest.digest().joinToString("") {
                    "%02x".format(it.toInt() and 255)
                }
                check(actualHash == expectedHash) { "Transferred PCM failed SHA-256 verification" }
                wav.commit()
            }
        }
        check(file.exists()) { "WAV file was not committed" }
        val stats = quality.result()
        // Use daemon clock sampled when AudioRecord started, not the time of
        // post-call file transfer. This makes media/call overlap calculations valid.
        val startNs = if (sourceStartNs > 0L) sourceStartNs else 0L
        RecordingDatabase(context).save(file, sourceName, actualRate, actualChannels,
            stats, expectedHash,
            startNs)
        check(command("DELETE " + sessionId) == "DELETED") { "Shell segment cleanup failed" }
        file
    }
}

