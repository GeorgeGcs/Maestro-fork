package maestro.harmonyos.driver

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

data class HarmonyOSLaunchTarget(val abilityName: String, val moduleName: String)

object HarmonyOSBundleMetadata {
    enum class DataClearSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }
    internal data class DebugPermission(val tokenId: Long, val granted: Boolean)

    private val mapper = ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val identifier = Regex("[A-Za-z0-9_][A-Za-z0-9_.$]*")

    fun parse(output: String, appId: String): JsonNode? {
        val bundle = parseObject(output) ?: return null
        val names = listOf(bundle.get("name"), bundle.get("bundleName"), bundle.path("appInfo").get("bundleName"),
            bundle.path("applicationInfo").get("bundleName")).mapNotNull { it?.textValue() }
        return bundle.takeIf { appId in names && names.all { it == appId } }
    }

    private fun parseObject(output: String): JsonNode? {
        val start = output.indexOf('{')
        if (start < 0) return null
        return try {
            mapper.readTree(output.substring(start))
        } catch (_: JsonProcessingException) {
            return null
        }?.takeIf(JsonNode::isObject)
    }

    internal fun debugPermission(bundle: JsonNode?, permission: String): DebugPermission? {
        if (bundle == null) return null
        val app = bundle.get("applicationInfo") ?: bundle.get("appInfo") ?: return null
        if (app.path("appProvisionType").textValue() != "debug" || !app.path("debug").booleanValue()) return null
        val token = app.path("accessTokenId")
        if (!token.isIntegralNumber || !token.canConvertToLong() || token.longValue() !in 1..0xFFFF_FFFFL) return null
        val permissions = bundle.path("reqPermissions").takeIf(JsonNode::isArray) ?: return null
        val states = bundle.path("reqPermissionStates")
        if (!states.isArray || states.size() != permissions.size()) return null
        val index = (0 until permissions.size()).singleOrNull { permissions[it].textValue() == permission } ?: return null
        val state = states[index]
        if (!state.isIntegralNumber || !state.canConvertToInt() || state.intValue() !in -1..0) return null
        return DebugPermission(token.longValue(), state.intValue() == 0)
    }

    internal fun isUserGrant(output: String, permission: String): Boolean =
        parseObject(output)?.let { it.path("permissionName").textValue() == permission &&
            it.path("grantMode").textValue() == "USER_GRANT" } == true

    fun dataClearSupport(bundle: JsonNode?): DataClearSupport {
        val application = bundle?.get("applicationInfo") ?: bundle?.get("appInfo") ?: return DataClearSupport.UNKNOWN
        val type = application.path("bundleType").takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
        val clearable = application.path("userDataClearable")
        if ((type != null && type != 0) || (clearable.isBoolean && !clearable.booleanValue())) return DataClearSupport.UNSUPPORTED
        if (type == 0 && clearable.isBoolean && clearable.booleanValue()) return DataClearSupport.SUPPORTED
        return DataClearSupport.UNKNOWN
    }

    fun launchTarget(bundle: JsonNode, moduleName: String?): HarmonyOSLaunchTarget? {
        val modules = bundle.path("hapModuleInfos").takeIf { it.isArray } ?: return null
        val candidates = modules.filter { module ->
            if (moduleName == null) module.path("moduleType").intValue() == 1
            else module.path("moduleName").textValue() == moduleName
        }
        val module = candidates.singleOrNull() ?: return null
        val name = module.path("moduleName").textValue()?.takeIf(identifier::matches) ?: return null
        val ability = listOf("mainElementName", "mainAbility").mapNotNull { key ->
            module.path(key).textValue()?.takeIf(String::isNotBlank)
        }.distinct().singleOrNull()?.takeIf(identifier::matches) ?: return null
        val abilities = module.path("abilityInfos").takeIf { it.isArray } ?: return null
        val declared = abilities.singleOrNull { it.path("name").textValue() == ability } ?: return null
        if (declared.path("visible").isBoolean && !declared.path("visible").booleanValue()) return null
        if (declared.path("enabled").isBoolean && !declared.path("enabled").booleanValue()) return null
        return HarmonyOSLaunchTarget(ability, name)
    }
}
