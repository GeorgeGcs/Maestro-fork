package maestro.harmonyos.layout

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import maestro.TreeNode

/** Parses the attributes/children format produced by OpenHarmony UITest dumpLayout. */
class UitestLayoutParser {

    fun parse(json: String): TreeNode {
        if (json.length > MAX_INPUT_CHARS) {
            throw UitestLayoutException("UITest layout exceeds $MAX_INPUT_CHARS characters")
        }
        val root = try {
            mapper.readTree(json)
        } catch (error: JsonProcessingException) {
            throw UitestLayoutException("Invalid UITest layout JSON", error)
        }
        if (root == null || root.isNull || root.isMissingNode) {
            throw UitestLayoutException("UITest layout is empty")
        }
        val state = ParseState()
        return if (root.isArray) {
            // dumpLayout -i emits a window array. This wrapper has no clickable coordinates.
            TreeNode(children = root.mapIndexed { index, node -> parseNode(node, "$[$index]", 0, state) })
        } else {
            parseNode(root, "$", 0, state)
        }
    }

    private fun parseNode(node: JsonNode, path: String, depth: Int, state: ParseState): TreeNode {
        if (depth > MAX_DEPTH) fail(path, "layout nesting exceeds $MAX_DEPTH")
        if (++state.nodes > MAX_NODES) fail(path, "layout contains more than $MAX_NODES nodes")
        if (!node.isObject) fail(path, "expected a node object")
        val source = node.get("attributes")
        if (source == null || !source.isObject) fail(path, "expected an attributes object")

        val attributes = linkedMapOf<String, String>()
        source.fields().forEach { (name, value) ->
            when {
                value.isNull -> Unit
                value.isValueNode -> attributes[name] = value.asText()
                else -> fail("$path.attributes.$name", "expected a scalar attribute")
            }
        }
        val clickable = booleanAttribute(source, "clickable", path)
        val enabled = booleanAttribute(source, "enabled", path)
        val focused = booleanAttribute(source, "focused", path)
        val checked = booleanAttribute(source, "checked", path)
        val selected = booleanAttribute(source, "selected", path)
        val visible = booleanAttribute(source, "visible", path)

        // id and key are InspectorKey in the referenced UITest implementation;
        // accessibilityId is a runtime identifier and must not become a resource-id.
        attributes.remove("resource-id")
        val id = attributes["id"]?.takeIf { it.isNotEmpty() }
            ?: attributes["key"]?.takeIf { it.isNotEmpty() }
        id?.let { attributes["resource-id"] = it }
        attributes.remove("hintText")
        attributes["hint"]?.let { attributes["hintText"] = it }
        attributes.remove("class")
        attributes["type"]?.let { attributes["class"] = it }

        attributes.remove("bounds")
        val bounds = parseBounds(source.get("bounds"), "$path.attributes.bounds")
        // Keep hidden ancestors for their children, but do not expose a hidden node as tappable.
        if (bounds != null && visible != false) attributes["bounds"] = bounds

        val children = node.get("children")
        if (children != null && !children.isNull && !children.isArray) {
            fail("$path.children", "expected an array")
        }
        return TreeNode(
            attributes = attributes,
            children = children?.takeUnless { it.isNull }?.mapIndexed { index, child ->
                parseNode(child, "$path.children[$index]", depth + 1, state)
            } ?: emptyList(),
            clickable = clickable,
            enabled = enabled,
            focused = focused,
            checked = checked,
            selected = selected,
        )
    }

    private fun booleanAttribute(attributes: JsonNode, name: String, path: String): Boolean? {
        val value = attributes.get(name) ?: return null
        if (value.isNull) return null
        if (value.isBoolean) return value.booleanValue()
        if (value.isTextual) {
            return when (value.textValue()) {
                "" -> null // The merged-window root uses empty strings for unavailable states.
                "true" -> true
                "false" -> false
                else -> fail("$path.attributes.$name", "expected true, false or an empty state")
            }
        }
        fail("$path.attributes.$name", "expected a boolean state")
    }

    private fun parseBounds(value: JsonNode?, path: String): String? {
        if (value == null || value.isNull || (value.isTextual && value.textValue().isEmpty())) return null
        if (!value.isTextual) fail(path, "expected bounds in [left,top][right,bottom] form")
        val match = BOUNDS.matchEntire(value.textValue())
            ?: fail(path, "expected bounds in [left,top][right,bottom] form")
        val (left, top, right, bottom) = match.groupValues.drop(1).map {
            it.toIntOrNull() ?: fail(path, "coordinate is outside the integer range")
        }
        val width = right.toLong() - left
        val height = bottom.toLong() - top
        if (width < 0 || height < 0) fail(path, "bounds have reversed edges")
        if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) fail(path, "bounds dimensions exceed the integer range")
        // UITest can report a zero-area synthetic root or clipped node. It has no tap target.
        if (width == 0L || height == 0L) return null
        return "[$left,$top][$right,$bottom]"
    }

    private fun fail(path: String, reason: String): Nothing = throw UitestLayoutException("$path: $reason")

    private class ParseState(var nodes: Int = 0)

    private companion object {
        const val MAX_INPUT_CHARS = 8 * 1024 * 1024
        const val MAX_DEPTH = 128
        const val MAX_NODES = 50_000
        val BOUNDS = Regex("\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]\\s*\\[\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*]")
        val mapper = ObjectMapper(
            JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build(),
        ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }
}

class UitestLayoutException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
