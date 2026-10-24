package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.config.ConfigGenerator
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.nekohasekai.sagernet.outbound.config.GeneratorSettings
import io.nekohasekai.sagernet.outbound.config.PortLease
import io.nekohasekai.sagernet.outbound.config.ProfileProvider
import io.nekohasekai.sagernet.outbound.config.RoutingInput
import io.nekohasekai.sagernet.outbound.config.TestCandidate
import io.nekohasekai.sagernet.route.RouteDns

/**
 * The config generator wired to the app: profiles come from the profile table, the settings and the build-time
 * globals from DataStore through [SettingsMapper], the current route profile and the rule-set list from
 * RouteManager (main configs only; test configs read only the profile's own DNS), the landing / front proxy from the
 * profile's group.
 */
object CoreConfigs {

    /** Stored profiles by id, each row parsed once per generation. */
    private class DatabaseProfiles : ProfileProvider {
        private val cache = HashMap<Long, Outbound?>()
        override fun get(id: Long): Outbound? = cache.getOrPut(id) { SagerDatabase.proxyDao.getById(id)?.outbound }
    }

    /** [dns]: the routing profile's own DNS, null for the global one; [settings] must be mapped with the same. */
    fun generator(
        routing: RoutingInput = RoutingInput.DEFAULT,
        settings: GeneratorSettings = SettingsMapper.generatorSettings(),
        dns: RouteDns? = null,
    ): ConfigGenerator = ConfigGenerator(DatabaseProfiles(), settings, SettingsMapper.buildContext(dns), routing)

    /**
     * The main config of [profile]; throws with the generator's message when it cannot be built. [deferRuleSets]
     * is GeneratorSettings.deferRuleSets, for a start the user let go without its rule-sets. A config that will run
     * leases its bridge ports to [ports] (ConfigGenerator.build). The DNS is that of the very profile routed.
     */
    fun buildMain(profile: ProxyEntity, deferRuleSets: Boolean = false, ports: PortLease? = null): GeneratedConfig {
        val (landing, front, groupName) = groupProxies(profile.groupId)
        val routing = SettingsMapper.routingInput()
        val dns = routing.profile?.activeDns()
        val settings = SettingsMapper.generatorSettings(dns).copy(deferRuleSets = deferRuleSets)
        val generated = generator(routing, settings, dns).build(profile.id, landing, front, ports, groupName)
        if (!generated.ok) error(generated.error ?: "config generation failed")
        return generated
    }

    /**
     * One test config for every candidate at once, each under its own group's landing / front proxy, with the DNS of
     * the current routing profile (BuildTestConfig sets ctx.dns).
     */
    fun buildTest(profileIds: List<Long>, ports: PortLease? = null): GeneratedConfig {
        val groups = HashMap<Long, Triple<Long, Long, String>>()
        val candidates = profileIds.map { id ->
            val groupId = SagerDatabase.proxyDao.getById(id)?.groupId ?: -1L
            val (landing, front, groupName) = groups.getOrPut(groupId) { groupProxies(groupId) }
            TestCandidate(id, landing, front, groupName)
        }
        val dns = RouteManager.customDns(DataStore.currentRouteId)
        return generator(settings = SettingsMapper.generatorSettings(dns), dns = dns).buildTest(candidates, ports)
    }

    /** (landing_proxy_id, front_proxy_id, name) of a group, -1 when unset (any id <= 0 is "none"). */
    private fun groupProxies(groupId: Long): Triple<Long, Long, String> {
        val group: ProxyGroup = (if (groupId > 0) SagerDatabase.groupDao.getById(groupId) else null) ?: return Triple(-1L, -1L, "")
        val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
        val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
        return Triple(landing, front, group.displayName())
    }
}
