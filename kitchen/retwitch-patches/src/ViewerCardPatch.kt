package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import app.morphe.patches.all.misc.resources.resourceMappingPatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Twitch's viewer card (tap on a chatter) opens in a bottom sheet dialog, a window of its own that the
 * activity's UI pass never sees. Its view binding (31.9.0 `mj3.b(LayoutInflater, ViewGroup)`, the only
 * static inflater of `layout/viewer_card`) hands the inflated root to `UserHistory.onViewerCard`, which
 * adds the message history row and our badge/nick paint while the card is shown.
 */
val retwitchViewerCardPatch = bytecodePatch(
    name = "ReTwitch viewer card",
    description = "Adds the message history, the RTE badge and the nick paint to the viewer card.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch, resourceMappingPatch)

    execute {
        try {
            val layout = getResourceId(ResourceType.LAYOUT, "viewer_card")
            fun Method.isInflater() = AccessFlags.STATIC.isSet(accessFlags) &&
                parameterTypes.map(Any::toString) == listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;") &&
                implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == layout } == true
            var owner: String? = null
            classDefForEach { c ->
                if (c.methods.any { it.isInflater() }) {
                    require(owner == null) { "two viewer card inflaters: $owner, ${c.type}" }
                    owner = c.type
                }
            }
            val method = mutableClassDefBy(owner ?: throw PatchException("viewer card inflater not found"))
                .methods.single { it.isInflater() }
            val code = method.instructions.toList()
            val literal = code.indexOfFirst { (it as? WideLiteralInstruction)?.wideLiteral == layout }
            val result = (literal until code.size).first { code[it].opcode == Opcode.MOVE_RESULT_OBJECT }
            val root = (code[result] as OneRegisterInstruction).registerA
            method.addInstructions(
                result + 1,
                "invoke-static/range {v$root .. v$root}, Ltv/retwitch/mod/UserHistory;->onViewerCard(Landroid/view/View;)V",
            )
            println("[ReTwitch] viewer card: $owner")
        } catch (throwable: Throwable) {
            println("[ReTwitch] viewer card skipped: $throwable")
        }
        try {
            val fragment = mutableClassDefBy("Ltv/twitch/android/feature/viewer/card/ViewerCardBottomSheetDialogFragment;")
            val getter = fragment.methods.single { it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
            val vm = mutableClassDefBy(getter.returnType)
            val query = vm.methods.single { it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == List(3) { "Ljava/lang/String;" } &&
                it.instructions.any { ins -> ins.opcode == Opcode.NEW_INSTANCE &&
                    classDefByOrNull { c -> c.type == (ins as ReferenceInstruction).reference.toString() }
                        ?.methods?.any { m -> m.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "ViewerCardInfo"
                        } == true } == true } }
            val bind = fragment.methods.single { it.name == "onViewCreated" }
            check(bind.implementation!!.registerCount in 5..15)
            bind.addInstructions(0, """
                const-string v0, "${getter.name}"
                const-string v1, "${query.name}"
                invoke-static {p0, p1, v0, v1}, Ltv/retwitch/mod/ViewerCardDetails;->bind(Ljava/lang/Object;Landroid/view/View;Ljava/lang/String;Ljava/lang/String;)V
            """.trimIndent())
            println("[ReTwitch] viewer card details: native retry ${vm.type}->${query.name}")
        } catch (t: Exception) {
            println("[ReTwitch] viewer card details skipped: $t")
        }
    }
}
