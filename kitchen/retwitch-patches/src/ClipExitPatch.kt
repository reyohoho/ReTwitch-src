package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

val retwitchClipExitPatch = bytecodePatch(
    name = "ReTwitch stop closed clips",
    description = "Pauses the outgoing clip's own player as soon as its viewer is removed.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch)
    execute {
        try {
            val fragment = mutableClassDefBy("Ltv/twitch/android/shared/clip/player/ClipPlayerFragment;")
            val getterRef = fragment.methods.single { it.name == "onPause" && it.parameterTypes.isEmpty() }
                .instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
                .single { it.definingClass == fragment.type && it.parameterTypes.isEmpty() && it.returnType.startsWith("L") }
            val getter = fragment.methods.single { it.name == getterRef.name && it.parameterTypes.isEmpty() }
            val presenter = getter.instructions.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
                .single { it.definingClass == fragment.type && it.type == getterRef.returnType }
            val owner = mutableClassDefBy(presenter.type)
            check(owner.methods.any {
                it.parameterTypes.map(CharSequence::toString) == listOf("Ltv/twitch/android/models/clips/ClipModel;") &&
                        it.returnType == "Ltv/twitch/android/models/clips/ClipAsset;"
            })
            // Its destroy method delegates to the same clip playback presenter. That presenter's pause also cancels its pending start.
            val destroy = owner.methods.single { it.name == "onDestroy" && it.parameterTypes.isEmpty() }
            val code = destroy.instructions.toList()
            val index = code.indices.single { i -> code[i].opcode == Opcode.IGET_OBJECT &&
                    (code.getOrNull(i + 1) as? ReferenceInstruction)?.reference.let { it is MethodReference && it.name == "onDestroy" } }
            val player = (code[index] as ReferenceInstruction).reference as FieldReference
            val base = ((code[index + 1] as ReferenceInstruction).reference as MethodReference).definingClass
            check(mutableClassDefBy(base).methods.any { it.name == "pause" && it.parameterTypes.isEmpty() && it.returnType == "V" })
            val close = fragment.methods.single { it.name == "onDestroyView" && it.parameterTypes.isEmpty() }
            check(close.implementation!!.registerCount in 2..16)
            close.addInstructionsWithLabels(0, """
                iget-object v0, p0, $presenter
                if-eqz v0, :rt_clip_exit_done
                iget-object v0, v0, $player
                invoke-static {p0, v0}, Ltv/retwitch/mod/ClipExit;->onDestroyView(Ljava/lang/Object;Ljava/lang/Object;)V
            """.trimIndent(), ExternalLabel("rt_clip_exit_done", close.getInstruction(0)))
            println("[ReTwitch] clip viewer exit: ${presenter.type} -> ${player.type}")
        } catch (e: Exception) {
            println("[ReTwitch] clip viewer exit skipped: $e")
        }
    }
}
