package com.vpnclient.app.core

import org.json.JSONObject

/**
 * Результат разбора одной ссылки подписки (vless://, hysteria2:// и т.д.).
 * outboundJson — уже готовый кусок конфига в формате sing-box (outbounds[]).
 */
data class ParsedServer(
    val name: String,
    val protocol: String,       // "vless" | "vmess" | "trojan" | "shadowsocks" | "hysteria2"
    val transportLabel: String, // напр. "GRPC", "WS", "TCP" — для строки протокола в UI
    val securityLabel: String,  // напр. "REALITY", "TLS", "" — для строки протокола в UI
    val server: String,
    val port: Int,
    val outboundJson: JSONObject
) {
    /** Строка вида "VLESS / GRPC / REALITY / JSON" как на скриншоте. */
    fun uiProtocolLine(): String {
        val parts = mutableListOf(protocol.uppercase())
        if (transportLabel.isNotBlank()) parts.add(transportLabel.uppercase())
        if (securityLabel.isNotBlank()) parts.add(securityLabel.uppercase())
        parts.add("JSON")
        return parts.joinToString(" / ")
    }
}
