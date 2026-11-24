package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

private const val PANEL = "tv.retwitch.mod.VodMetadataPanel"

private val vodMetadataLayout = resourcePatch {
    execute {
        try {
            document("res/layout/player_view.xml").use { doc ->
                val frames = doc.getElementsByTagName("FrameLayout")
                val old = (0 until frames.length).map { frames.item(it) as Element }
                    .single { it.getAttribute("android:id") == "@id/metadata_coordinator_container" }
                check(!old.hasChildNodes())
                val panel = doc.createElement(PANEL)
                for (i in 0 until old.attributes.length) {
                    val attr = old.attributes.item(i)
                    panel.setAttribute(attr.nodeName, attr.nodeValue)
                }
                old.parentNode.replaceChild(panel, old)
            }
        } catch (e: Exception) {
            println("[ReTwitch] VOD metadata layout skipped: $e")
        }
    }
}

/** The VOD presenter's visibility write is shared with other delegates. The hook delegates every other view unchanged. */
val retwitchVodMetadataPatch = bytecodePatch(
    name = "ReTwitch VOD metadata overlay",
    description = "Optionally fades portrait video details over chat replay.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch, vodMetadataLayout)
    execute {
        try {
            val delegate = mutableClassDefBy(classDefBy("Ltv/twitch/android/core/mvp/viewdelegate/BaseViewDelegate;"))
            val sites = delegate.methods.filter { it.parameterTypes.map(CharSequence::toString) == listOf("I") && it.returnType == "V" }
                .flatMap { method ->
                    method.implementation?.instructions.orEmpty().mapIndexedNotNull { index, insn ->
                        if (insn.opcode == Opcode.INVOKE_VIRTUAL &&
                            (insn as? ReferenceInstruction)?.reference.toString() == "Landroid/view/View;->setVisibility(I)V"
                        ) Triple(method, index, insn as FiveRegisterInstruction) else null
                    }
                }
            val (method, index, call) = sites.single()
            method.replaceInstruction(index, "invoke-static {v${call.registerC}, v${call.registerD}}, Ltv/retwitch/mod/VodMetadataPanel;->setDelegateVisibility(Landroid/view/View;I)V")
        } catch (e: Exception) {
            println("[ReTwitch] VOD metadata fade skipped: $e")
        }
    }
}
