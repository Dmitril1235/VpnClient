package com.vpnclient.app.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Собирает полный конфиг sing-box (JSON-строка) из одного выбранного сервера.
 * Формат — тот же, что использует официальный sing-box-for-android (SFA):
 * tun-инбаунд с auto_route, наш outbound с тегом "proxy", DNS через прокси,
 * маршрут по умолчанию — в прокси.
 */
object ConfigBuilder {

    fun build(server: ParsedServer): String {
        // Тег "proxy" нужен фиксированным, чтобы на него могли сослаться route.final и dns.
        val outbound = JSONObject(server.outboundJson.toString())
        outbound.put("tag", "proxy")

        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                put("level", "info")
                put("timestamp", true)
            })

            put("dns", JSONObject().apply {
                put("servers", JSONArray().apply {
                    put(JSONObject().apply {
                        put("tag", "dns-remote")
                        put("address", "tls://1.1.1.1")
                        put("detour", "proxy")
                    })
                    put(JSONObject().apply {
                        put("tag", "dns-direct")
                        put("address", "223.5.5.5")
                        put("detour", "direct")
                    })
                })
                put("final", "dns-remote")
                put("independent_cache", true)
            })

            put("inbounds", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("interface_name", "tun0")
                    put("inet4_address", "172.19.0.1/30")
                    put("inet6_address", "fdfe:dcba:9876::1/126")
                    put("mtu", 9000)
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "gvisor")
                    put("sniff", true)
                })
            })

            put("outbounds", JSONArray().apply {
                put(outbound)
                put(JSONObject().apply {
                    put("type", "direct")
                    put("tag", "direct")
                })
                put(JSONObject().apply {
                    put("type", "dns")
                    put("tag", "dns-out")
                })
            })

            put("route", JSONObject().apply {
                put("auto_detect_interface", true)
                put("final", "proxy")
                put("rules", JSONArray().apply {
                    put(JSONObject().apply {
                        put("protocol", "dns")
                        put("outbound", "dns-out")
                    })
                })
            })
        }

        return config.toString()
    }
}
