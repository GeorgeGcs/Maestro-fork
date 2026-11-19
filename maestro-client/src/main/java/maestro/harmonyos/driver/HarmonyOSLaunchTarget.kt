package maestro.harmonyos.driver

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

data class HarmonyOSLaunchTarget(val abilityName: String, val moduleName: String)

object HarmonyOSBundleMetadata {
    enum class DataClearSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

    private val mapper = ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val identifier = Regex("[A-Za-z0-9_][A-Za-z0-9_.$]*")

    fun parse(output: String, appId: String): JsonNode? {
        val start = output.indexOf('{')
        if (start < 0) return null
        val bundle = try {
            mapper.readTree(output.substring(start))
        } catch (_: JsonProcessingException) {
            return null
        }
        if (!bundle.isObject) return null
        val names = listOf(bundle.get("name"), bundle.get("bundleName"), bundle.path("appInfo").get("bundleName"),
            bundle.path("applicationInfo").get("bundleName")).mapNotNull { it?.textValue() }
        return bundle.takeIf { appId in names && names.all { it == appId } }
    }

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
