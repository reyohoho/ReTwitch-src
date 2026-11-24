package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

val twitchLanguagePatch = bytecodePatch(
    name = "ReTwitch interface language",
    description = "Selects the Twitch interface language independently from the mod settings.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch)
    execute {
        val app = mutableClassDefByOrNull { it.type == "Ltv/twitch/android/app/consumer/TwitchApplication;" }
        // The common AppCompat activity that prepares the base context for all Twitch screens.
        var type: String? = "Ltv/twitch/android/feature/viewer/landing/ViewerLandingActivity;"
        var wrapped = false
        while (type != null) {
            val cls = classDefByOrNull { it.type == type } ?: break
            if (cls.methods.any { it.name == "attachBaseContext" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/content/Context;") }) {
                val method = mutableClassDefByOrNull { it.type == type }?.methods?.single { it.name == "attachBaseContext" }
                method?.addInstructions(0, """
                    invoke-static/range {p1 .. p1}, Ltv/retwitch/mod/TwitchLanguage;->wrap(Landroid/content/Context;)Landroid/content/Context;
                    move-result-object p1
                """.trimIndent())
                wrapped = method != null
                break
            }
            type = cls.superclass
        }
        if (wrapped) {
            app?.methods?.singleOrNull { it.name == "onCreate" && it.parameterTypes.isEmpty() }?.addInstructions(0,
                "invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/TwitchLanguage;->install(Landroid/app/Application;)V")
        } else println("[ReTwitch] Legacy Twitch language hook skipped: no activity attachBaseContext")
    }
}
