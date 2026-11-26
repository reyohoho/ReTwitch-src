package tv.retwitch.patches

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.hasResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import org.w3c.dom.Element
import com.reandroid.arsc.value.ResValue
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
private const val ACCENT_100 = 0x0106003a
private const val ACCENT_600 = 0x0106003f
private const val ACCENT_700 = 0x01060040
private const val ACCENT_800 = 0x01060041

/** Only UI roles. The Twitch base ramps are also used by badges, gradients, statuses and video overlays. */
private fun nativePaletteTable(packageName: String, fixed: Boolean, materialTones: Map<String, Int>,
                               colorId: (String) -> Int?): ByteArray {
    val table = TableBlock()
    val pkg = table.newPackage(0x7f, packageName)
    val day = ResConfig.parse("")
    val night = ResConfig.parse("night")
    val values = mutableListOf<ResValue>()
    val lightness = intArrayOf(100, 99, 95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 0)
    fun put(name: String, config: ResConfig, data: Int) {
        val id = colorId(name) ?: return
        val type = id ushr 16 and 0xff
        pkg.getOrCreateTypeString(type, "color")
        val entry = pkg.getOrCreateEntry(type.toByte(), id.toShort(), config)
        entry.ensureComplex(false)
        entry.setName(name)
        entry.resValue.valueType = if (fixed) ValueType.COLOR_ARGB8 else ValueType.REFERENCE
        entry.resValue.data = data
        if (fixed) values.add(entry.resValue)
    }
    fun roles(light: Int, dark: Int, vararg names: String) {
        for (name in names) {
            for ((config, reference) in listOf(day to light, night to dark)) {
                val index = reference - NEUTRAL_0
                put(name, config, if (fixed) (index / 13) * 128 + lightness[index % 13] else reference)
            }
        }
    }
    roles(NEUTRAL_10, NEUTRAL_900, "background_body", "bottom_bar_background", "rt_palette_chat_panel")
    roles(NEUTRAL_0, MUTED_900, "background_base", "search_input_focused_background")
    roles(NEUTRAL_50, MUTED_800, "background_float", "background_alt", "callout_background")
    roles(NEUTRAL_100, MUTED_700, "background_alt2")
    roles(NEUTRAL_900, NEUTRAL_50, "text_base", "bottom_tab_inactive", "text_tab_item", "text_button_secondary")
    roles(MUTED_700, MUTED_200, "text_alt")
    roles(MUTED_600, MUTED_300, "text_alt_2", "data_text_subtitle", "search_input_inactive_tint")
    roles(MUTED_200, MUTED_700, "border_region")
    roles(ACCENT_700, ACCENT_300, "border_brand", "fill_brand", "text_brand", "text_link", "text_tab_item_active",
        "bottom_tab_active", "button_label_text", "search_input_background_stroke", "switch_thumb_color_active",
        "progress_bar_progress", "checkbox_themed_color_checked", "slider_active", "toggle_thumb_active_themed_color",
        "radio_button_active_themed_color", "radio_button_tint_checked", "message_input_button_on",
        "subscribe_cta_text_color", "twitch_tint_color")
    roles(ACCENT_100, ACCENT_800, "toggle_track_active_themed_color")
    roles(ACCENT_300, ACCENT_700, "pressed")
    roles(ACCENT_600, ACCENT_300, "rt_palette_points_shape")
    roles(ACCENT_700, ACCENT_600, "rt_palette_points_shadow")
    roles(ACCENT_100, ACCENT_100, "rt_palette_points_detail")
    // Brand button text is white in both themes, so its filled background stays dark enough.
    roles(ACCENT_600, ACCENT_600, "background_accent", "button_background_default", "background_button_brand")
    roles(ACCENT_700, ACCENT_700, "background_accent_alt", "background_button_brand_hover")
    roles(ACCENT_800, ACCENT_800, "background_accent_alt2", "background_button_brand_active")
    if (fixed) {
        // Android 14+ overrides the Material 3 roles with framework wallpaper roles.
        for ((name, tone) in materialTones) for (qualifier in listOf("v31", "v34")) {
            put(name, ResConfig.parse(qualifier), tone)
        }
    }
    table.refreshFull()
    val bytes = table.bytes
    if (!fixed) return bytes
    val little = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val file = ByteArrayOutputStream()
    DataOutputStream(file).use { out ->
        out.writeInt(bytes.size)
        out.write(bytes)
        out.writeInt(values.size)
        for (value in values) {
            val offset = table.countUpTo(value) + 4
            check(bytes[offset - 1].toInt() == 0x1c && little.getInt(offset) == value.data)
            out.writeInt(offset)
        }
    }
    return file.toByteArray()
}

internal val retwitchSystemPaletteResources = resourcePatch {
    dependsOn(resourceMappingPatch)
    execute {
        val stock = mapOf("rt_palette_chat_panel" to "@color/transparent_background",
            "rt_palette_points_shape" to "#ff5c16c5", "rt_palette_points_shadow" to "#ff772ce8",
            "rt_palette_points_detail" to "#ffede0ff")
        // Morphe's PublicXmlManager assigns appended colours consecutively in values declaration order.
        val lastColor = document(get("res/values/public.xml").inputStream()).use { doc ->
            val entries = doc.getElementsByTagName("public")
            (0 until entries.length).asSequence().map { entries.item(it) as Element }
                .filter { it.getAttribute("type") == "color" }.maxOf { it.getAttribute("id").removePrefix("0x").toInt(16) }
        }
        val ids = stock.keys.mapIndexed { index, name -> name to lastColor + index + 1 }.toMap()
        document("res/values/colors.xml").use { doc ->
            stock.forEach { (name, fallback) ->
                doc.documentElement.appendChild(doc.createElement("color").apply {
                    setAttribute("name", name)
                    textContent = fallback
                })
            }
        }
        fun redirect(path: String, tag: String, attribute: String, expected: String, replacement: String, id: String? = null) {
            document(path).use { doc ->
                val nodes = doc.getElementsByTagName(tag)
                var changed = 0
                for (i in 0 until nodes.length) {
                    val node = nodes.item(i) as Element
                    val value = node.getAttribute(attribute)
                    val matches = value == expected || expected.startsWith("#ff") && (
                        value.equals(expected, ignoreCase = true) || value.equals("#" + expected.drop(3), ignoreCase = true))
                    if ((id == null || node.getAttribute("android:id") == "@id/$id") && matches) {
                        node.setAttribute(attribute, replacement)
                        changed++
                    }
                }
                if (changed == 0) println("[ReTwitch] wallpaper colours: skipped $path, missing $attribute=$expected")
            }
        }
        for (layout in listOf("theatre_coordinator", "theatre_coordinator_land")) {
            redirect("res/layout/$layout.xml", "FrameLayout", "android:background", "@color/transparent_background",
                "@color/rt_palette_chat_panel", "chat_wrapper")
        }
        // The original button-brand role and twitch_purple both resolve to #9147ff in both themes.
        redirect("res/layout/social_media_link_item.xml", "ImageView", "app:tint", "@color/twitch_purple",
            "@color/background_button_brand", "image")
        for ((name, fallback) in stock.filterKeys { it.startsWith("rt_palette_points_") }) {
            redirect("res/drawable/community_points_artwork.xml", "path", "android:fillColor", fallback, "@color/$name")
        }
        redirect("res/drawable/bg_stream_button.xml", "solid", "android:color", "@color/twitch_purple",
            "@color/background_button_brand")
        redirect("res/layout/subscription_product_section_actions_default.xml", "ImageView", "app:tint", "@color/twitch_purple",
            "@color/background_button_brand", "title_icon_image_view")
        val materialTones = mutableMapOf<String, Int>()
        val ramp = Regex("""@color/m3_ref_palette_dynamic_(neutral_variant|neutral|primary|secondary|tertiary)(\d+)""")
        document(get("res/values-v31/colors.xml").inputStream()).use { doc ->
            val colors = doc.getElementsByTagName("color")
            for (i in 0 until colors.length) {
                val node = colors.item(i) as Element
                val name = node.getAttribute("name")
                if (!name.startsWith("m3_sys_color_dynamic_")) continue
                val match = ramp.matchEntire(node.textContent.trim()) ?: continue
                val family = listOf("neutral", "neutral_variant", "primary", "secondary", "tertiary").indexOf(match.groupValues[1])
                materialTones[name] = family * 128 + match.groupValues[2].toInt()
            }
        }
        check(materialTones.isNotEmpty())
        for ((file, fixed) in listOf("retwitch-palette.arsc" to false, "retwitch-palette-fixed.bin" to true)) {
            val bytes = nativePaletteTable(NEW_PACKAGE_NAME, fixed, materialTones) { name ->
                ids[name] ?: if (hasResourceId(ResourceType.COLOR, name)) getResourceId(ResourceType.COLOR, name).toInt() else null
            }
            get("assets/$file", false).apply { parentFile.mkdirs() }.writeBytes(bytes)
            println("[ReTwitch] system palette: $file (${bytes.size} bytes)")
        }
    }
}
