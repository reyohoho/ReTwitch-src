package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.hasResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType

// Public framework ids (Android 12 android.R.color). References track wallpaper changes without a new table.
private const val NEUTRAL_0 = 0x0106001d
private const val NEUTRAL_10 = 0x0106001e
private const val NEUTRAL_50 = 0x0106001f
private const val NEUTRAL_100 = 0x01060020
private const val NEUTRAL_900 = 0x01060028
private const val MUTED_200 = 0x0106002e
private const val MUTED_300 = 0x0106002f
private const val MUTED_600 = 0x01060032
private const val MUTED_700 = 0x01060033
private const val MUTED_800 = 0x01060034
private const val MUTED_900 = 0x01060035
private const val ACCENT_300 = 0x0106003c
private const val ACCENT_600 = 0x0106003f
private const val ACCENT_700 = 0x01060040
private const val ACCENT_800 = 0x01060041

/** Only UI roles. The Twitch base ramps are also used by badges, gradients, statuses and video overlays. */
internal fun nativePaletteTable(packageName: String, colorId: (String) -> Int?): ByteArray {
    val table = TableBlock()
    val pkg = table.newPackage(0x7f, packageName)
    val day = ResConfig.parse("")
    val night = ResConfig.parse("night")

    fun roles(light: Int, dark: Int, vararg names: String) {
        for (name in names) {
            val id = colorId(name) ?: continue
            val type = id ushr 16 and 0xff
            pkg.getOrCreateTypeString(type, "color")
            for ((config, reference) in listOf(day to light, night to dark)) {
                val entry = pkg.getOrCreateEntry(type.toByte(), id.toShort(), config)
                entry.ensureComplex(false)
                entry.setName(name)
                entry.resValue.valueType = ValueType.REFERENCE
                entry.resValue.data = reference
            }
        }
    }
    roles(NEUTRAL_10, NEUTRAL_900, "background_body", "bottom_bar_background")
    roles(NEUTRAL_0, MUTED_900, "background_base", "search_input_focused_background")
    roles(NEUTRAL_50, MUTED_800, "background_float", "background_alt", "callout_background")
    roles(NEUTRAL_100, MUTED_700, "background_alt2")
    roles(NEUTRAL_900, NEUTRAL_50, "text_base", "bottom_tab_inactive", "text_tab_item", "text_button_secondary")
    roles(MUTED_700, MUTED_200, "text_alt")
    roles(MUTED_600, MUTED_300, "text_alt_2", "data_text_subtitle", "search_input_inactive_tint")
    roles(MUTED_200, MUTED_700, "border_region")
    roles(ACCENT_700, ACCENT_300, "border_brand", "fill_brand", "text_brand", "text_link", "text_tab_item_active",
        "bottom_tab_active", "button_label_text", "search_input_background_stroke", "switch_thumb_color_active",
        "progress_bar_progress")
    // Brand button text is white in both themes, so its filled background stays dark enough.
    roles(ACCENT_600, ACCENT_600, "background_accent", "button_background_default", "background_button_brand")
    roles(ACCENT_700, ACCENT_700, "background_accent_alt", "background_button_brand_hover")
    roles(ACCENT_800, ACCENT_800, "background_accent_alt2", "background_button_brand_active")
    table.refreshFull()
    return table.bytes
}

internal val retwitchSystemPaletteResources = resourcePatch {
    dependsOn(resourceMappingPatch)
    execute {
        val bytes = nativePaletteTable(NEW_PACKAGE_NAME) { name ->
            if (hasResourceId(ResourceType.COLOR, name)) getResourceId(ResourceType.COLOR, name).toInt() else null
        }
        get("assets/retwitch-palette.arsc", false).apply { parentFile.mkdirs() }.writeBytes(bytes)
        println("[ReTwitch] system palette: native light/dark table (${bytes.size} bytes)")
    }
}
