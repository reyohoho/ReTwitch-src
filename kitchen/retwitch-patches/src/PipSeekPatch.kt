package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * The native theatre's picture-in-picture actions (31.9.0 `a7r.e(ZZ)`): play/pause into a list, the list into
 * `PictureInPictureParams.Builder.setActions`, then "update_actions".
 */
val pipActionsFingerprint = fingerprint {
    returns("V")
    parameters("Z", "Z")
    strings("tv.twitch.android.media.action.resume_playback", "update_actions")
}

/** Issue #58: back and forward around play/pause in the PiP window of a VOD (VodSeek.pipActions). */
val retwitchPipSeekPatch = bytecodePatch(
    name = "ReTwitch PiP seek",
    description = "Back and forward buttons in the picture-in-picture window of a VOD, if set.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val method = pipActionsFingerprint.method
            val code = method.instructions.toList()
            val call = code.indices.single {
                code[it].opcode == Opcode.INVOKE_VIRTUAL &&
                    code[it].getReference<MethodReference>()?.let { ref ->
                        ref.definingClass == "Landroid/app/PictureInPictureParams\$Builder;" && ref.name == "setActions"
                    } == true
            }
            val list = (code[call] as FiveRegisterInstruction).registerD
            if (list > 255) throw PatchException("list register out of range")
            method.addInstructions(
                call,
                """
                    invoke-static/range {v$list .. v$list}, Ltv/retwitch/mod/VodSeek;->pipActions(Ljava/util/List;)Ljava/util/List;
                    move-result-object v$list
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] PiP seek skipped: $e")
        }
    }
}
