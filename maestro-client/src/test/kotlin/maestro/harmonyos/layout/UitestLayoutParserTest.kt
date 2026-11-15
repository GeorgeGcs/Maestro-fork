package maestro.harmonyos.layout

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class UitestLayoutParserTest {
    private val parser = UitestLayoutParser()

    @Test
    fun `merged layout maps stable ids text bounds and state fields`() {
        val json = checkNotNull(javaClass.getResource("/harmonyos/layout-merged.json")).readText()
        val root = parser.parse(json)
        val button = root.children.single().children.first()

        assertThat(button.attributes["resource-id"]).isEqualTo("submit")
        assertThat(button.attributes["text"]).isEqualTo("提交订单")
        assertThat(button.attributes["bounds"]).isEqualTo("[24,120][400,200]")
        assertThat(button.enabled).isTrue()
        assertThat(button.clickable).isTrue()
        assertThat(button.checked).isFalse()
        assertThat(button.selected).isFalse()
        assertThat(button.focused).isFalse()
        assertThat(button.attributes["class"]).isEqualTo("Button")
        assertThat(root.enabled).isNull()
        assertThat(root.clickable).isNull()
    }

    @Test
    fun `hint and accessibility text retain separate attributes`() {
        val json = checkNotNull(javaClass.getResource("/harmonyos/layout-merged.json")).readText()
        val root = parser.parse(json)
        val input = root.children.single().children.last()

        assertThat(input.attributes["hintText"]).isEqualTo("请输入姓名")
        assertThat(input.attributes["accessibilityText"]).isEqualTo("收件人姓名")
        assertThat(input.attributes["text"]).isEmpty()
        assertThat(input.focused).isTrue()
        assertThat(input.enabled).isNull()
    }

    @Test
    fun `window array preserves order and adds no synthetic coordinates`() {
        val root = parser.parse("""[
            {"attributes":{"id":"window-a","bounds":"[0,0][100,200]"},"children":[]},
            {"attributes":{"id":"window-b","bounds":"[10,20][90,150]"},"children":[]}
        ]""")

        assertThat(root.children.map { it.attributes["resource-id"] }).containsExactly("window-a", "window-b").inOrder()
        assertThat(root.attributes).doesNotContainKey("bounds")
        assertThat(parser.parse("[]").children).isEmpty()
    }

    @Test
    fun `missing states and bounds stay unknown`() {
        val node = parser.parse("""{"attributes":{"text":"无坐标","enabled":"","checked":null}}""")

        assertThat(node.enabled).isNull()
        assertThat(node.checked).isNull()
        assertThat(node.clickable).isNull()
        assertThat(node.attributes).doesNotContainKey("bounds")
    }

    @Test
    fun `key can supply stable id but accessibility id cannot`() {
        val keyed = parser.parse("""{"attributes":{"id":"","key":"save","accessibilityId":"42"}}""")
        val unkeyed = parser.parse("""{"attributes":{"accessibilityId":"42"}}""")

        assertThat(keyed.attributes["resource-id"]).isEqualTo("save")
        assertThat(unkeyed.attributes).doesNotContainKey("resource-id")
        assertThat(unkeyed.attributes["accessibilityId"]).isEqualTo("42")
    }

    @Test
    fun `boolean states are accepted without treating arbitrary strings as false`() {
        val node = parser.parse("""{"attributes":{"enabled":true,"checked":false}}""")
        assertThat(node.enabled).isTrue()
        assertThat(node.checked).isFalse()
        listOf("\"unknown\"", "1", "[]").forEach { value ->
            assertThrows(UitestLayoutException::class.java) {
                parser.parse("""{"attributes":{"enabled":$value}}""")
            }
        }
    }

    @Test
    fun `negative coordinates and spaces are normalized without clipping`() {
        val node = parser.parse("""{"attributes":{"bounds":"[ -10, 20 ][ 90, 100 ]"}}""")
        assertThat(node.attributes["bounds"]).isEqualTo("[-10,20][90,100]")
    }

    @Test
    fun `hidden and zero area nodes do not expose tap coordinates`() {
        val hidden = parser.parse("""{"attributes":{"visible":"false","bounds":"[1,2][10,20]"},
            "children":[{"attributes":{"text":"child","visible":"true","bounds":"[2,3][8,9]"}}]}""")
        assertThat(hidden.attributes).doesNotContainKey("bounds")
        assertThat(hidden.children.single().attributes["bounds"]).isEqualTo("[2,3][8,9]")
        val empty = parser.parse("""{"attributes":{"bounds":"[0,0][0,0]"}}""")
        assertThat(empty.attributes).doesNotContainKey("bounds")
    }

    @Test
    fun `invalid bounds fail with the node path`() {
        listOf("[5,1][4,2]", "[0,5][1,4]", "[0,0,10,20]", "[0,0][2147483648,2]", "[-2147483648,0][1,2]").forEach { bounds ->
            val error = assertThrows(UitestLayoutException::class.java) {
                parser.parse("""{"attributes":{},"children":[{"attributes":{"bounds":"$bounds"}}]}""")
            }
            assertThat(error).hasMessageThat().contains("$.children[0].attributes.bounds")
        }
        assertThrows(UitestLayoutException::class.java) {
            parser.parse("""{"attributes":{"bounds":[0,0,100,100]}}""")
        }
    }

    @Test
    fun `malformed JSON and node shapes fail rather than return an empty tree`() {
        listOf("", "null", "{}", "true", "{", "{\"attributes\":[]}",
            "{\"attributes\":{},\"children\":{}}", "{\"attributes\":{}} {\"attributes\":{}}",
            "{\"attributes\":{\"text\":\"a\",\"text\":\"b\"}}", "[null]").forEach { json ->
            assertThrows(UitestLayoutException::class.java) { parser.parse(json) }
        }
    }

    @Test
    fun `deep layout fails before recursive TreeNode consumers can overflow`() {
        var json = """{"attributes":{}}"""
        repeat(130) { json = """{"attributes":{},"children":[$json]}""" }
        val error = assertThrows(UitestLayoutException::class.java) { parser.parse(json) }
        assertThat(error).hasMessageThat().contains("nesting")
    }
}
