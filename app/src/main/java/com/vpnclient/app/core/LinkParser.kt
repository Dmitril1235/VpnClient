package com.vpnclient.app.core

import android.util.Base64
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Разбирает одну ссылку сервера (vless://, vmess://, trojan://, ss://, hysteria2://)
 * и превращает её в ParsedServer с готовым outbound-конфигом для sing-box.
 * Возвращает null, если ссылка не распознана или битая — такие просто пропускаем
 * при разборе подписки, не роняя весь список.
 */
object LinkParser {

    fun parse(rawLink: String): ParsedServer? {
        val link = rawLink.trim()
        return try {
            when {
                link.startsWith("vless://") -> parseVless(link)
                link.startsWith("vmess://") -> parseVmess(link)
                link.startsWith("trojan://") -> parseTrojan(link)
                link.startsWith("ss://") -> parseShadowsocks(link)
                link.startsWith("hysteria2://") || link.startsWith("hy2://") -> parseHysteria2(link)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- VLESS ----------
    // vless://uuid@host:port?encryption=none&security=reality&sni=..&fp=..&pbk=..&sid=..&type=grpc&serviceName=..&flow=..#remark
    private fun parseVless(link: String): ParsedServer {
        val uri = URI(link)
        val uuid = uri.userInfo ?: error("no uuid")
        val host = uri.host ?: error("no host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery)
        val remark = decodeFragment(uri.rawFragment) ?: host

        val security = params["security"] ?: "none"
        val transportType = params["type"] ?: "tcp"

        val outbound = JSONObject().apply {
            put("type", "vless")
            put("tag", remark)
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            params["flow"]?.let { if (it.isNotBlank()) put("flow", it) }

            if (security == "tls" || security == "reality") {
                val tls = JSONObject().apply {
                    put("enabled", true)
                    params["sni"]?.let { put("server_name", it) }
                    if (params["allowInsecure"] == "1") put("insecure", true)
                    params["fp"]?.let {
                        put("utls", JSONObject().apply {
                            put("enabled", true)
                            put("fingerprint", it)
                        })
                    }
                    if (security == "reality") {
                        put("reality", JSONObject().apply {
                            put("enabled", true)
                            put("public_key", params["pbk"] ?: "")
                            params["sid"]?.let { put("short_id", it) }
                        })
                    }
                }
                put("tls", tls)
            }

            put("transport", buildTransport(transportType, params))
        }

        return ParsedServer(
            name = remark,
            protocol = "vless",
            transportLabel = transportType,
            securityLabel = security.takeIf { it != "none" } ?: "",
            server = host,
            port = port,
            outboundJson = outbound
        )
    }

    // ---------- VMess ----------
    // vmess://base64(JSON: v, ps, add, port, id, aid, net, type, host, path, tls, sni)
    private fun parseVmess(link: String): ParsedServer {
        val b64 = link.removePrefix("vmess://")
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))

        val host = json.optString("add")
        val port = json.optInt("port", 443)
        val remark = json.optString("ps", host)
        val net = json.optString("net", "tcp")
        val tls = json.optString("tls", "")

        val outbound = JSONObject().apply {
            put("type", "vmess")
            put("tag", remark)
            put("server", host)
            put("server_port", port)
            put("uuid", json.optString("id"))
            put("security", "auto")
            put("alter_id", json.optInt("aid", 0))

            if (tls == "tls") {
                put("tls", JSONObject().apply {
                    put("enabled", true)
                    val sni = json.optString("sni", json.optString("host"))
                    if (sni.isNotBlank()) put("server_name", sni)
                })
            }

            val params = mutableMapOf<String, String>()
            json.optString("host").takeIf { it.isNotBlank() }?.let { params["host"] = it }
            json.optString("path").takeIf { it.isNotBlank() }?.let { params["path"] = it }
            put("transport", buildTransport(net, params))
        }

        return ParsedServer(
            name = remark,
            protocol = "vmess",
            transportLabel = net,
            securityLabel = if (tls == "tls") "TLS" else "",
            server = host,
            port = port,
            outboundJson = outbound
        )
    }

    // ---------- Trojan ----------
    // trojan://password@host:port?security=tls&sni=..&type=ws&path=..#remark
    private fun parseTrojan(link: String): ParsedServer {
        val uri = URI(link)
        val password = uri.userInfo ?: error("no password")
        val host = uri.host ?: error("no host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery)
        val remark = decodeFragment(uri.rawFragment) ?: host
        val transportType = params["type"] ?: "tcp"

        val outbound = JSONObject().apply {
            put("type", "trojan")
            put("tag", remark)
            put("server", host)
            put("server_port", port)
            put("password", password)
            put("tls", JSONObject().apply {
                put("enabled", true)
                params["sni"]?.let { put("server_name", it) }
                if (params["allowInsecure"] == "1") put("insecure", true)
            })
            put("transport", buildTransport(transportType, params))
        }

        return ParsedServer(
            name = remark,
            protocol = "trojan",
            transportLabel = transportType,
            securityLabel = "TLS",
            server = host,
            port = port,
            outboundJson = outbound
        )
    }

    // ---------- Shadowsocks ----------
    // Формат SIP002: ss://base64(method:password)@host:port#remark
    // Легаси:        ss://base64(method:password@host:port)#remark
    private fun parseShadowsocks(link: String): ParsedServer {
        val withoutScheme = link.removePrefix("ss://")
        val hashIdx = withoutScheme.indexOf('#')
        val remarkRaw = if (hashIdx >= 0) withoutScheme.substring(hashIdx + 1) else null
        val body = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme

        val atIdx = body.lastIndexOf('@')
        val method: String
        val password: String
        val host: String
        val port: Int

        if (atIdx >= 0) {
            // SIP002: [base64 или plain method:password]@host:port
            val userInfoRaw = body.substring(0, atIdx)
            val hostPort = body.substring(atIdx + 1)
            val decodedUserInfo = tryBase64Decode(userInfoRaw) ?: userInfoRaw
            val sepIdx = decodedUserInfo.indexOf(':')
            method = decodedUserInfo.substring(0, sepIdx)
            password = decodedUserInfo.substring(sepIdx + 1)
            val portIdx = hostPort.lastIndexOf(':')
            host = hostPort.substring(0, portIdx)
            port = hostPort.substring(portIdx + 1).toInt()
        } else {
            // Легаси: всё внутри base64
            val decoded = String(Base64.decode(body, Base64.DEFAULT))
            val sepIdx = decoded.indexOf('@')
            val methodPass = decoded.substring(0, sepIdx)
            val hostPort = decoded.substring(sepIdx + 1)
            val mpIdx = methodPass.indexOf(':')
            method = methodPass.substring(0, mpIdx)
            password = methodPass.substring(mpIdx + 1)
            val portIdx = hostPort.lastIndexOf(':')
            host = hostPort.substring(0, portIdx)
            port = hostPort.substring(portIdx + 1).toInt()
        }

        val remark = remarkRaw?.let { URLDecoder.decode(it, "UTF-8") } ?: host

        val outbound = JSONObject().apply {
            put("type", "shadowsocks")
            put("tag", remark)
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }

        return ParsedServer(
            name = remark,
            protocol = "shadowsocks",
            transportLabel = "",
            securityLabel = "",
            server = host,
            port = port,
            outboundJson = outbound
        )
    }

    // ---------- Hysteria2 ----------
    // hysteria2://password@host:port?insecure=1&sni=..&obfs=salamander&obfs-password=..#remark
    private fun parseHysteria2(link: String): ParsedServer {
        val normalized = link.replaceFirst("hy2://", "hysteria2://")
        val uri = URI(normalized)
        val password = uri.userInfo ?: ""
        val host = uri.host ?: error("no host")
        val port = if (uri.port > 0) uri.port else 443
        val params = parseQuery(uri.rawQuery)
        val remark = decodeFragment(uri.rawFragment) ?: host

        val outbound = JSONObject().apply {
            put("type", "hysteria2")
            put("tag", remark)
            put("server", host)
            put("server_port", port)
            put("password", password)
            put("tls", JSONObject().apply {
                put("enabled", true)
                params["sni"]?.let { put("server_name", it) }
                if (params["insecure"] == "1") put("insecure", true)
            })
            params["obfs"]?.let { obfsType ->
                put("obfs", JSONObject().apply {
                    put("type", obfsType)
                    params["obfs-password"]?.let { put("password", it) }
                })
            }
        }

        return ParsedServer(
            name = remark,
            protocol = "hysteria2",
            transportLabel = "",
            securityLabel = "TLS",
            server = host,
            port = port,
            outboundJson = outbound
        )
    }

    // ---------- Вспомогательное ----------

    private fun buildTransport(type: String, params: Map<String, String>): JSONObject {
        return JSONObject().apply {
            when (type) {
                "grpc" -> {
                    put("type", "grpc")
                    params["serviceName"]?.let { put("service_name", it) }
                }
                "ws" -> {
                    put("type", "ws")
                    params["path"]?.let { put("path", it) }
                    params["host"]?.let {
                        put("headers", JSONObject().apply { put("Host", it) })
                    }
                }
                "http" -> {
                    put("type", "http")
                    params["path"]?.let { put("path", it) }
                    params["host"]?.let { put("host", it) }
                }
                else -> put("type", "tcp")
            }
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) return@mapNotNull null
            val key = pair.substring(0, idx)
            val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            key to value
        }.toMap()
    }

    private fun decodeFragment(rawFragment: String?): String? {
        if (rawFragment.isNullOrBlank()) return null
        return try {
            URLDecoder.decode(rawFragment, "UTF-8")
        } catch (e: Exception) {
            rawFragment
        }
    }

    private fun tryBase64Decode(s: String): String? {
        return try {
            String(Base64.decode(s, Base64.DEFAULT))
        } catch (e: Exception) {
            null
        }
    }
}
