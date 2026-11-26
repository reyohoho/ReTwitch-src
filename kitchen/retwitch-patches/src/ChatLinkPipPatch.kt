package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction

private val externalBrowserFingerprint = fingerprint {
    returns("Z")
    strings("android.intent.action.VIEW", "https://www.google.com")
    custom { method, _ ->
        method.implementation?.instructions?.any { (it as? WideLiteralInstruction)?.wideLiteral == 0x40000L } == true
    }
}

val retwitchChatLinkPipPatch = bytecodePatch(
    name = "ReTwitch picture in picture for chat links",
    description = "Preserves Twitch's automatic picture-in-picture when a chat link opens the browser.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val method = externalBrowserFingerprint.method
            // The warning (if enabled) has already been accepted and Twitch has selected an external package.
            val launch = method.instructions.withIndex().single {
                (it.value as? ReferenceInstruction)?.reference.toString() ==
                    "Landroid/content/Context;->startActivity(Landroid/content/Intent;)V"
            }
            val call = launch.value as FiveRegisterInstruction
            method.addInstructions(launch.index,
                "invoke-static {v${call.registerC}, v${call.registerD}}, Ltv/retwitch/mod/PipGuard;->beforeBrowserLaunch(Landroid/content/Context;Landroid/content/Intent;)V")
            println("[ReTwitch] chat link PiP: ${method.definingClass}->${method.name}")
        } catch (e: Exception) {
            println("[ReTwitch] chat link PiP skipped: $e")
        }
    }
}
