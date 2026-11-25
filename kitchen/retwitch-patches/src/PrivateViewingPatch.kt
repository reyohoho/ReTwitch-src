package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

private const val PRIVATE_VIEW = "Ltv/retwitch/mod/PrivateViewing;"

private val viewedVideoMutation = fingerprint { strings("UpdateUserViewedVideoMutation") }
private val videoPositionReader = fingerprint {
    parameters("Ltv/twitch/android/models/videos/VodModel;")
    returns("Ljava/lang/Integer;")
    custom { m, _ -> m.implementation?.instructions?.any {
        (it as? ReferenceInstruction)?.reference.toString() ==
                "Ltv/twitch/android/models/videos/VodModel;->getViewingHistoryPositionSec()Ljava/lang/Integer;"
    } == true }
}
private val viewingSpadeDispatch = fingerprint {
    returns("V")
    strings("https://spade.twitch.tv", "x_untrusted_minute-watched_spade")
    custom { m, _ -> m.parameterTypes.take(2).map(Any::toString) ==
            listOf("Ljava/lang/String;", "Ljava/util/LinkedHashMap;") }
}

private val composeApolloRequest = fingerprint {
    strings("X-APOLLO-CAN-BE-BATCHED")
    custom { m, _ -> m.name == "invokeSuspend" }
}
private val playbackAuthInterceptor = fingerprint { strings("OAuth ", "Authorization", "Accept-Language", "Api-Consumer-Type") }
private val redirectAuthHeader = fingerprint { strings("Received HTTP_PROXY_AUTH (407) code while not using proxy", "Authorization", "PROPFIND") }
private fun Instruction.field() = (this as ReferenceInstruction).reference as FieldReference
private fun Instruction.methodRef() = (this as ReferenceInstruction).reference as MethodReference
private fun Instruction.stringValue() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

val retwitchPrivateViewingPatch = bytecodePatch(
    name = "ReTwitch private viewing",
    description = "Uses Twitch's no-history branch for private playback and filters viewing events before Spade dispatch.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch)

    execute {
        val mutation = viewedVideoMutation.classDef.type
        val writer = mutableClassDefBy(videoPositionReader.classDef.type).methods.single { m ->
            m.parameterTypes.map(Any::toString).sorted() == listOf("I", "Ljava/lang/String;", "Ltv/twitch/android/models/Tuid;", "Z") &&
                m.instructions.any { it.opcode == Opcode.NEW_INSTANCE && (it as ReferenceInstruction).reference.toString() == mutation }
        }
        // Enter the same empty Completable branch as a signed-out viewer before even touching the RAM cache.
        val w = writer.instructions.toList()
        val guest = w.indexOfFirst { it.opcode == Opcode.IF_EQZ }
        require(guest > 1 && w[guest - 1].opcode == Opcode.MOVE_RESULT &&
                (w[guest - 2] as? ReferenceInstruction)?.reference.toString().endsWith("()Z"))
        val gate = (w[guest] as OneRegisterInstruction).registerA
        require(gate == (w[guest - 1] as OneRegisterInstruction).registerA)
        // At entry v0 is a scratch local; the guest branch ends in its own no-op return.
        val empty = w.lastIndex - 1
        require(writer.implementation!!.registerCount > 5 && w[empty].opcode == Opcode.SGET_OBJECT &&
                w.last().opcode == Opcode.RETURN_OBJECT &&
                (w[guest] as com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction).target.location.index == empty)
        writer.addInstructionsWithLabels(0, """
            sget-boolean v0, ${PRIVATE_VIEW}->ENABLED:Z
            if-nez v0, :guest
        """.trimIndent(), ExternalLabel("guest", writer.getInstruction(empty)))

        val event = viewingSpadeDispatch.method
        require(event.implementation!!.registerCount > event.parameterTypes.size + 1)
        event.addInstructionsWithLabels(0, """
            invoke-static/range {p1 .. p1}, ${PRIVATE_VIEW}->skipEvent(Ljava/lang/String;)Z
            move-result v0
            if-eqz v0, :keep
            return-void
        """.trimIndent(), ExternalLabel("keep", event.getInstruction(0)))
        // Isolate token operations before Apollo batches them. Its own non-batch flag keeps a
        // simultaneous chat or subscription operation on the authenticated transport.
        val compose = composeApolloRequest.method
        val ci = compose.instructions.toList()
        val batch = ci.indexOfFirst { it.stringValue() == "X-APOLLO-CAN-BE-BATCHED" }
        val saved = (batch - 1 downTo 0).first { ci[it].opcode == Opcode.IPUT_OBJECT }
        val headers = ci[saved].field()
        val batchFlag = ci[saved + 1].field()
        val request = (ci[saved] as TwoRegisterInstruction).registerB
        val tmp = (ci[saved] as TwoRegisterInstruction).registerA
        val key = (ci[batch] as OneRegisterInstruction).registerA
        val create = (batch + 1 until batch + 19).first { ci[it].opcode == Opcode.NEW_INSTANCE }
        val entry = (ci[create] as OneRegisterInstruction).registerA
        val headerCtor = ci[create + 1].methodRef()
        val append = ci[create + 2].methodRef()
        val opCtor = classDefBy(headers.definingClass).methods.single { m -> m.name == "<init>" &&
                m.parameterTypes.size == 1 && classDefBy(m.parameterTypes[0].toString()).methods.any {
                    it.name == "document" && it.returnType == "Ljava/lang/String;"
                } }
        val operation = opCtor.parameterTypes.single().toString()
        val opField = opCtor.implementation!!.instructions.first { it.opcode == Opcode.IPUT_OBJECT &&
                (it as TwoRegisterInstruction).registerA == opCtor.implementation!!.registerCount - 1 }.field()
        require(listOf(request, tmp, key, entry).distinct().size == 4 && ci[saved+1].opcode == Opcode.IGET_OBJECT &&
                headerCtor.parameterTypes.map(Any::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;") &&
                append.parameterTypes.map(Any::toString) == listOf("Ljava/util/Collection;", "Ljava/lang/Object;"))
        compose.addInstructionsWithLabels(saved + 1, """
            sget-boolean v$tmp, ${PRIVATE_VIEW}->ENABLED:Z
            if-eqz v$tmp, :keep
            iget-object v$tmp, v$request, $opField
            check-cast v$tmp, $operation
            invoke-interface {v$tmp}, ${operation}->name()Ljava/lang/String;
            move-result-object v$tmp
            invoke-static {v$tmp}, ${PRIVATE_VIEW}->tokenOperation(Ljava/lang/String;)Z
            move-result v$tmp
            if-eqz v$tmp, :keep
            const-string v$key, "Authorization"
            const-string v$entry, "ReTwitch-Guest-Playback"
            new-instance v$tmp, ${headerCtor.definingClass}
            invoke-direct {v$tmp, v$key, v$entry}, $headerCtor
            iget-object v$key, v$request, $headers
            check-cast v$key, Ljava/util/Collection;
            invoke-static {v$key, v$tmp}, $append
            move-result-object v$tmp
            iput-object v$tmp, v$request, $headers
            sget-object v$tmp, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            iput-object v$tmp, v$request, $batchFlag
        """.trimIndent(), ExternalLabel("keep", compose.getInstruction(saved + 1)))

        // At Twitch's auth interceptor remove the local marker, then use its real signed-out
        // headers. OkHttp's redirect logic gives us the obfuscated builder's remove-header API.
        val redirect = redirectAuthHeader.method.instructions.toList()
        val rem = redirect.indexOfFirst { it.stringValue() == "Authorization" }
        require(redirect[rem+1].opcode == Opcode.IGET_OBJECT && redirect[rem+2].opcode == Opcode.CHECK_CAST)
        val builderHeaders = redirect[rem+1].field()
        val headerBuilder = (redirect[rem+2] as ReferenceInstruction).reference
        val removeHeader = redirect[rem+3].methodRef()
        require(removeHeader.definingClass == headerBuilder.toString() && removeHeader.parameterTypes.single().toString() == "Ljava/lang/String;")
        val auth = playbackAuthInterceptor.method
        val ai = auth.instructions.toList()
        val accept = ai.indexOfFirst { it.stringValue() == "Accept" }
        val read = ai[accept+1].methodRef()
        val lookup = ai[accept-1] as TwoRegisterInstruction
        val headerKey = (ai.single { it.stringValue() == "PUT" } as OneRegisterInstruction).registerA
        val login = (accept+1 until ai.size).first { ai[it].opcode == Opcode.CHECK_CAST } + 1
        val source = lookup.registerB
        val value = (ai[accept] as OneRegisterInstruction).registerA
        val builder = (ai[accept-2] as FiveRegisterInstruction).registerC
        val write = ai.indexOfFirst { it.stringValue() == "Authorization" } + 1
        require(ai[write].opcode == Opcode.INVOKE_VIRTUAL && ai[write+1].opcode == Opcode.IGET_OBJECT)
        // At the account lookup, Accept is already set. Both scratch registers are overwritten
        // by the original code before reuse; leave the session holder live on the regular path.
        require(ai[login].opcode == Opcode.INVOKE_VIRTUAL && ai[login+1].opcode == Opcode.MOVE_RESULT_OBJECT &&
                (ai[login+1] as OneRegisterInstruction).registerA == value)
        auth.addInstructionsWithLabels(login, """
            sget-boolean v$value, ${PRIVATE_VIEW}->ENABLED:Z
            if-eqz v$value, :regular
            iget-object v$value, v$source, ${ai[accept-1].field()}
            const-string v$headerKey, "Authorization"
            invoke-virtual {v$value, v$headerKey}, $read
            move-result-object v$value
            invoke-static {v$value}, ${PRIVATE_VIEW}->guestMarker(Ljava/lang/String;)Z
            move-result v$value
            if-eqz v$value, :regular
            iget-object v$value, v$builder, $builderHeaders
            check-cast v$value, $headerBuilder
            invoke-virtual {v$value, v$headerKey}, $removeHeader
            goto/16 :guest
        """.trimIndent(), ExternalLabel("regular", auth.getInstruction(login)), ExternalLabel("guest", auth.getInstruction(write+1)))
        // ClipQuery, feeds and channel shelves carry an account token alongside personalized fields.
        // Keep those operations signed in; replace the media source only when ClipAsset produces it.
        val clip = mutableClassDefBy("Ltv/twitch/android/models/clips/ClipAsset;")
        val model = "Ltv/twitch/android/models/clips/ClipAccessTokenModel;"
        val tokenField = clip.fields.single { it.type == model }
        val manifest = clip.methods.single { it.name == "toManifest" && it.parameterTypes.size == 1 }
        val mi = manifest.instructions.toList()
        val tokenGetters = mi.indices.filter { i ->
            val ref = (mi[i] as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == model && ref.name in listOf("getToken", "getSignature")
        }
        require(tokenGetters.size == 2)
        for (i in tokenGetters.reversed()) {
            require(mi[i+1].opcode == Opcode.MOVE_RESULT_OBJECT)
            val result = (mi[i+1] as OneRegisterInstruction).registerA
            manifest.addInstructions(i+2, """
                invoke-static {v$result}, Ltv/retwitch/mod/PrivateClipPlayback;->manifestCredential(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$result
            """.trimIndent())
        }
        val direct = clip.methods.single { it.name == "getBestUrlForQuality" && it.parameterTypes.size == 2 }
        val directCode = direct.instructions.toList()
        val sourceCall = directCode.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getClosestQualityUrl" }
        require(sourceCall >= 0 && directCode[sourceCall+1].opcode == Opcode.MOVE_RESULT_OBJECT)
        val rendition = (directCode[sourceCall+1] as OneRegisterInstruction).registerA
        val tracked = clip.methods.single { it.name == "withItemTrackingId" && it.parameterTypes.size == 2 }
        require(mi.any { (it as? ReferenceInstruction)?.reference.toString() == tracked.toString() })
        for ((method, start, url) in listOf(Triple(direct, sourceCall+2, "v$rendition"), Triple(tracked, 0, "p1"))) {
            require(method.implementation!!.registerCount >= 4)
            method.addInstructionsWithLabels(start, """
                sget-boolean v0, ${PRIVATE_VIEW}->ENABLED:Z
                if-eqz v0, :regularClip
                iget-object v0, p0, $tokenField
                if-nez v0, :readSlug
                const/4 v0, 0x0
                goto :localClip
                :readSlug
                invoke-virtual {v0}, ${model}->getToken()Ljava/lang/String;
                move-result-object v0
                :localClip
                invoke-static {$url, v0}, Ltv/retwitch/mod/PrivateClipPlayback;->localUrl(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """.trimIndent(), ExternalLabel("regularClip", method.getInstruction(start)))
        }
        println("[ReTwitch] private viewing: ${writer.definingClass}->${writer.name}, ${event.definingClass}->${event.name}")
    }
}
