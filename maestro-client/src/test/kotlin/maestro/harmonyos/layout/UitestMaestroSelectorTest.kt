package maestro.harmonyos.layout

import com.google.common.truth.Truth.assertThat
import maestro.Filters
import maestro.UiElement.Companion.toUiElementOrNull
import org.junit.jupiter.api.Test

/** Integration checks against the real Maestro selectors and coordinate conversion. */
class UitestMaestroSelectorTest {
    private val parser = UitestLayoutParser()

    @Test
    fun `converted layout works with Maestro id text and state selectors`() {
        val json = checkNotNull(javaClass.getResource("/harmonyos/layout-merged.json")).readText()
        val root = parser.parse(json)
        val nodes = root.aggregate()
        val button = root.children.single().children.first()

        assertThat(Filters.idMatches("submit".toRegex())(nodes)).containsExactly(button)
        assertThat(Filters.textMatches("提交订单".toRegex())(nodes)).containsExactly(button)
        assertThat(Filters.enabled(true)(nodes)).contains(button)
        assertThat(Filters.checked(false)(nodes)).containsExactly(button)
        assertThat(button.toUiElementOrNull()?.bounds?.width).isEqualTo(376)
        assertThat(button.toUiElementOrNull()?.bounds?.height).isEqualTo(80)
    }

    @Test
    fun `hint and accessibility text remain searchable while unknown state stays unmatched`() {
        val json = checkNotNull(javaClass.getResource("/harmonyos/layout-merged.json")).readText()
        val root = parser.parse(json)
        val input = root.children.single().children.last()

        assertThat(Filters.textMatches("请输入姓名".toRegex())(root.aggregate())).containsExactly(input)
        assertThat(Filters.textMatches("收件人姓名".toRegex())(root.aggregate())).containsExactly(input)
        assertThat(Filters.enabled(false)(listOf(input))).isEmpty()
        assertThat(Filters.enabled(true)(listOf(input))).isEmpty()
    }

    @Test
    fun `coordinate conversion retains offsets and rejects nodes without tap bounds`() {
        val node = parser.parse("""{"attributes":{"bounds":"[ -10, 20 ][ 90, 100 ]"}}""")
        assertThat(node.toUiElementOrNull()?.bounds?.x).isEqualTo(-10)
        assertThat(node.toUiElementOrNull()?.bounds?.y).isEqualTo(20)
        assertThat(node.toUiElementOrNull()?.bounds?.width).isEqualTo(100)

        listOf("{}", "{\"bounds\":\"[0,0][0,0]\"}",
            "{\"bounds\":\"[0,0][10,10]\",\"visible\":\"false\"}").forEach { attributes ->
            assertThat(parser.parse("""{"attributes":$attributes}""").toUiElementOrNull()).isNull()
        }
        assertThat(parser.parse("[]").toUiElementOrNull()).isNull()
    }
}
