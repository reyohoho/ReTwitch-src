package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

val retwitchTimerPatch = resourcePatch(
    name = "ReTwitch sleep/exit timer",
    description = "Adds the optional sleep timer next to the player settings button.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch)

    execute {
        for (layout in listOf("player_control_overlay", "player_control_overlay_widget")) {
            try {
                document("res/layout/$layout.xml").use { doc ->
                    val nodes = doc.getElementsByTagName("*")
                    fun button(id: String) = (0 until nodes.length).map { nodes.item(it) as Element }
                        .single { it.getAttribute("android:id") == "@id/$id" }
                    val share = button("share_button")
                    val gear = button("settings_button")
                    val end = (0 until share.attributes.length).map { share.attributes.item(it).nodeName }
                        .single { it.endsWith(":layout_constraintEnd_toStartOf") }
                    require(share.getAttribute(end) == "@id/settings_button" && share.parentNode == gear.parentNode)
                    val start = end.substringBefore(':') + ":layout_constraintStart_toEndOf"
                    val clock = doc.createElement("view")
                    clock.setAttribute("class", "tv.retwitch.mod.TimerHook\$ClockButton")
                    clock.setAttribute("android:id", "@+id/retwitch_sleep_timer")
                    for (i in 0 until share.attributes.length) {
                        val attr = share.attributes.item(i)
                        if (":layout_" in attr.nodeName) clock.setAttribute(attr.nodeName, attr.nodeValue)
                    }
                    if (gear.getAttribute(start) == "@id/share_button") {
                        clock.setAttribute(start, "@id/share_button")
                        gear.setAttribute(start, "@id/retwitch_sleep_timer")
                    }
                    share.setAttribute(end, "@id/retwitch_sleep_timer")
                    share.parentNode.insertBefore(clock, gear)
                }
            } catch (e: Exception) {
                println("[ReTwitch] timer layout $layout skipped: $e")
            }
        }
    }
}
