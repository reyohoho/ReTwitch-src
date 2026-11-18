package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch

/** `toString()` of the native Continue Watching answer (31.9.0 `jsx`: the VOD list and their saved places). */
val resumeWatchingResponseFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("ResumeWatchingResponse(resumeWatchingVods=")
}

/** Issue #57: the native Continue Watching shelf without videos watched to the end, through RnFollowingHook. */
val retwitchResumeWatchingPatch = bytecodePatch(
    name = "ReTwitch resume watching",
    description = "Continue Watching drops videos watched to the end, if set.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val init = mutableClassDefBy(resumeWatchingResponseFingerprint.classDef.type).methods.single {
                it.name == "<init>" && it.parameterTypes.map(Any::toString) ==
                    listOf("Ljava/util/List;", "Ljava/util/LinkedHashMap;")
            }
            init.addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p2}, Ltv/retwitch/mod/RnFollowingHook;->resumeWatching(Ljava/util/List;Ljava/util/Map;)Ljava/util/List;
                    move-result-object p1
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] resume watching skipped: $e")
        }
    }
}
