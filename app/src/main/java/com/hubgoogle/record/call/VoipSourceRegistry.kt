package com.hubgoogle.record.call

/** Only notification call classification; never evidence of a capturable voice stream. */
object VoipSourceRegistry {
    val packages: Map<String, String> = linkedMapOf(
        "jp.naver.line.android" to "VOIP_LINE",
        "com.whatsapp" to "VOIP_WHATSAPP",
        "com.whatsapp.w4b" to "VOIP_WHATSAPP_BUSINESS",
        "org.telegram.messenger" to "VOIP_TELEGRAM",
        "org.thoughtcrime.securesms" to "VOIP_SIGNAL",
        "com.facebook.orca" to "VOIP_MESSENGER",
        "com.discord" to "VOIP_DISCORD",
        "com.google.android.apps.tachyon" to "VOIP_MEET"
    )
    fun source(packageName: String): String? = packages[packageName]
}
