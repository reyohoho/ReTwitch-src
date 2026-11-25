package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private val readRecentSearches = fingerprint {
    returns("Ljava/util/ArrayList;")
    strings("recentSearches")
    custom { method, _ -> method.parameterTypes.isEmpty() }
}

private val saveRecentSearches = fingerprint {
    returns("V")
    strings("recentSearches")
    custom { method, _ ->
        method.parameterTypes.isEmpty() && method.name != "<clinit>" && method.implementation?.instructions?.any {
            (it as? ReferenceInstruction)?.reference.toString() == "Lorg/json/JSONArray;-><init>(Ljava/util/Collection;)V"
        } == true
    }
}

/** Browse and the search screen share a mutable history list. The observer also reads directly from disk. */
val retwitchBrowseSearchHistoryPatch = bytecodePatch(
    name = "ReTwitch Browse search history",
    description = "Optionally hides recent Twitch searches and stops adding new ones to local history.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val read = readRecentSearches.method
            val writer = saveRecentSearches.method
            val add = mutableClassDefBy(writer.definingClass).methods.single { method ->
                method.parameterTypes.map { it.toString() } == listOf("Ljava/lang/String;") && method.returnType == "V" &&
                    method.instructions.any {
                        (it as? ReferenceInstruction)?.reference.toString() == "Ljava/util/ArrayList;->add(ILjava/lang/Object;)V"
                    }
            }
            // At entry v0 is a local in both methods. Leave both the on-disk list and the remove action untouched.
            require(read.implementation!!.registerCount > 1 && add.implementation!!.registerCount > 2)
            for ((method, disabled) in listOf(read to """
                    new-instance v0, Ljava/util/ArrayList;
                    invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V
                    return-object v0""", add to "return-void")) {
                method.addInstructionsWithLabels(0, """
                    sget-boolean v0, Ltv/retwitch/mod/BrowseSearchHistory;->DISABLED:Z
                    if-eqz v0, :keep
                    $disabled
                """.trimIndent(), ExternalLabel("keep", method.getInstruction(0)))
            }
            println("[ReTwitch] Browse search history: ${read.definingClass}->${read.name}, ${add.definingClass}->${add.name}")
        } catch (e: Exception) {
            println("[ReTwitch] Browse search history skipped: $e")
        }
    }
}
