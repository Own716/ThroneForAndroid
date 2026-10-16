package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vtr

/** chain (include/configs/outbounds/chain.h): a list of profile ids the generator expands into hops (R2 §F). */
class Chain : Outbound("chain") {

    /** chain.h:10: from in to out — list[0] is the hop the client dials first, the last entry is the exit. */
    @JvmField var list: ArrayList<Long> = ArrayList()

    /** chain.h:12. */
    override fun displayType(): String = "Chain Proxy"

    /** chain.h:14. */
    override fun displayAddress(): String = ""

    /** chain.h:17: no security of its own; it inherits whatever its hops use. */
    override fun security(): SecurityInfo = SecurityInfo()

    /** chain.h: holds no secret itself; every hop is checked separately. */
    override fun supportsCredentialStrip(): Boolean = true

    /** chain.h: the hops carry the servers. */
    override fun hasServerEndpoint(): Boolean = false

    /** chain.h Validate: the hop rules need the local profiles, so the chain editor checks them at save. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        if (list.isEmpty()) issues.add(Issue(Severity.Error, "list", vtr(R.string.validation_chain_empty)))
    }

    /** chain.h:22-27 (`list` items via QJsonValue::toInt; widened to Long for Room ids). */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("name")) name = obj.string("name")
        if (obj.contains("list")) list = obj.array("list").mapTo(ArrayList()) { JsonValues.toInteger(it) }
        return true
    }

    /** chain.h:29-35: `name` (even empty), `type` and `list` (even empty) are always written. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["name"] = name
        obj["type"] = "chain"
        val ids = JsonArray()
        for (id in list) ids.add(id)
        obj["list"] = ids
        return obj
    }

    /** chain.h:37-40. */
    override fun build(ctx: BuildContext): BuildResult = BuildResult(JsonObject(), "Cannot call Build on chain config")
}
