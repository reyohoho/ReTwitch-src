package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch

/**
 * `toString()` of the chat rules state (31.9.0 `bp6`, "Initialized(willShowRules=, rules=)"): every reader asks its
 * flag through the base class's getter (the input's focus, the send button).
 */
val chatRulesStateFingerprint = fingerprint {
    returns("Ljava/lang/String;")
    strings("Initialized(willShowRules=")
}

/** Issue #60: the "Chat rules" sheet before the first message on a channel, through LiveChatHook.chatRules. */
val retwitchChatRulesPatch = bytecodePatch(
    name = "ReTwitch chat rules",
    description = "No chat rules sheet before the first message on a channel, if set.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        try {
            val init = mutableClassDefBy(chatRulesStateFingerprint.classDef.type).methods.single {
                it.name == "<init>" && it.parameterTypes.map(Any::toString) ==
                    listOf("Z", "Ltv/twitch/android/models/chat/ChatRulesModel;")
            }
            init.addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, Ltv/retwitch/mod/LiveChatHook;->chatRules(Z)Z
                    move-result p1
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] chat rules skipped: $e")
        }
    }
}
