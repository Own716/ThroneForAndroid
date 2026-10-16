package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SendHwid
import io.nekohasekai.sagernet.database.SubHttpVersion
import io.nekohasekai.sagernet.database.SubTlsVersion
import io.nekohasekai.sagernet.ktx.appUserAgent

/** RequestIdentity (GroupUpdater.hpp): the user agent, HWID headers and TLS / HTTP versions of a subscription request. */
class RequestIdentity(
    @JvmField val userAgent: String,
    @JvmField val sendHwid: Boolean,
    @JvmField val device: DeviceDetails,
    @JvmField val tlsVersion: SubTlsVersion = SubTlsVersion.AUTO,
    @JvmField val httpVersion: SubHttpVersion = SubHttpVersion.AUTO,
) {

    /** identityHeaders (GroupUpdater.cpp:59-70). */
    fun headers(): List<Pair<String, String>> {
        if (!sendHwid) return emptyList()
        val headers = ArrayList<Pair<String, String>>(4)
        fun add(name: String, value: String) {
            if (usableHeaderValue(value)) headers.add(name to value)
        }
        add("x-hwid", device.hwid)
        add("x-device-os", device.os)
        add("x-ver-os", device.osVersion)
        add("x-device-model", device.model)
        return headers
    }

    companion object {

        private const val MAX_HEADER_VALUE = 1000

        /** usableHeaderValue (GroupUpdater.cpp:40-42). */
        @JvmStatic
        fun usableHeaderValue(value: String): Boolean =
            value.isNotEmpty() && value.length < MAX_HEADER_VALUE && '\n' !in value && '\r' !in value

        /** applyCustomHwidParams (GroupUpdater.cpp:44-57): `hwid=…,os=…,osVersion=…,model=…`, keys case-insensitive. */
        @JvmStatic
        fun applyCustomHwidParams(device: DeviceDetails, params: String): DeviceDetails {
            var result = device
            for (pair in params.split(',')) {
                val trimmed = pair.trim()
                val eqPos = trimmed.indexOf('=')
                if (eqPos <= 0) continue
                val key = trimmed.substring(0, eqPos).trim().lowercase()
                val value = trimmed.substring(eqPos + 1).trim()
                if (!usableHeaderValue(value)) continue
                result = when (key) {
                    "hwid" -> result.copy(hwid = value)
                    "os" -> result.copy(os = value)
                    "osversion" -> result.copy(osVersion = value)
                    "model" -> result.copy(model = value)
                    else -> result
                }
            }
            return result
        }

        /**
         * ResolveIdentity (GroupUpdater.cpp:299-319): the global subscription settings under the group's overrides;
         * a null [group] resolves the globals alone (ImportUrl and the Advanced placeholders).
         */
        @JvmStatic
        fun resolve(group: ProxyGroup?): RequestIdentity {
            var userAgent = appUserAgent()
            var sendHwid = DataStore.subSendHwid
            var device = applyCustomHwidParams(DeviceDetails.get(), DataStore.subCustomHwidParams)
            var tlsVersion = SubTlsVersion.of(DataStore.subTlsVersion) ?: SubTlsVersion.AUTO
            var httpVersion = SubHttpVersion.of(DataStore.subHttpVersion) ?: SubHttpVersion.AUTO
            if (group == null) return RequestIdentity(userAgent, sendHwid, device, tlsVersion, httpVersion)

            val options = group.subOptions
            if (usableHeaderValue(options.userAgent)) userAgent = options.userAgent
            options.tlsVersion?.let { tlsVersion = it }
            options.httpVersion?.let { httpVersion = it }
            if (options.sendHwid != SendHwid.KEEP_DEFAULT) sendHwid = options.sendHwid == SendHwid.ON
            if (usableHeaderValue(options.hwid)) device = device.copy(hwid = options.hwid)
            if (usableHeaderValue(options.hwidOs)) device = device.copy(os = options.hwidOs)
            if (usableHeaderValue(options.hwidOsVersion)) device = device.copy(osVersion = options.hwidOsVersion)
            if (usableHeaderValue(options.hwidModel)) device = device.copy(model = options.hwidModel)
            return RequestIdentity(userAgent, sendHwid, device, tlsVersion, httpVersion)
        }
    }
}
