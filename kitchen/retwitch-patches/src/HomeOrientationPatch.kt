package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val ACTIVITY = "Landroid/app/Activity;"

/**
 * Home in landscape: every `Activity.setRequestedOrientation(int)` call in the app (17 in 31.9.0: Twitch's orientation
 * helper `dbp`, the RN orientation module, rnscreens...) goes through `HomeOrientation.request`, which lets a portrait
 * lock of the home through or turns it into USER/LOCKED (LandingSettings home rotation). Skipped softly.
 */
val retwitchHomeOrientationPatch = bytecodePatch(
    name = "ReTwitch home orientation",
    description = "Optionally lets the home screen turn to landscape.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            fun isCall(insn: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
                insn.opcode == Opcode.INVOKE_VIRTUAL && ((insn as ReferenceInstruction).reference as? MethodReference)?.let {
                    it.definingClass == ACTIVITY && it.name == "setRequestedOrientation" &&
                        it.parameterTypes.map { p -> p.toString() } == listOf("I") && it.returnType == "V"
                } == true
            var hooked = 0
            classDefForEach { classDef ->
                if (classDef.type.startsWith("Ltv/retwitch/")) return@classDefForEach
                if (classDef.methods.none { m -> m.implementation?.instructions?.any(::isCall) == true }) return@classDefForEach
                mutableClassDefBy(classDef).methods.forEach { m ->
                    val code = m.implementation?.instructions?.toList() ?: return@forEach
                    code.indices.filter { isCall(code[it]) }.forEach { i ->
                        val call = code[i] as FiveRegisterInstruction
                        m.replaceInstruction(
                            i,
                            "invoke-static {v${call.registerC}, v${call.registerD}}, " +
                                "Ltv/retwitch/mod/HomeOrientation;->request(Landroid/app/Activity;I)V",
                        )
                        hooked++
                    }
                }
            }
            println("[ReTwitch] home orientation: $hooked setRequestedOrientation call(s)")
        } catch (e: Exception) {
            println("[ReTwitch] home orientation skipped: $e")
        }
    }
}
