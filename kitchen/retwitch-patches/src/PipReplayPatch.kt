package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val REPLAY = "Ltv/retwitch/mod/PipReplay;"
private val replayActionHandler = fingerprint { strings("replay_button") }
private val seekableControls = fingerprint {
    strings("processStateUpdate(Ltv/twitch/android/shared/player/overlay/seekable/SeekableOverlayPresenter\$State;" +
            "Ltv/twitch/android/shared/player/overlay/seekable/SeekableOverlayPresenter\$UpdateEvent;)Ltv/twitch/android/core/mvp/presenter/StateAndAction;")
}

/** The stock overlay handles REPLAY for both VODs and clips. Follow its VOD binding and actual button render. */
val retwitchPipReplayPatch = bytecodePatch(
    name = "ReTwitch PiP replay",
    description = "Offers the native VOD Replay action in system picture-in-picture after playback ends.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchPipSeekPatch)

    execute {
        try {
            val presenter = mutableClassDefBy(seekableControls.classDef.type)
            fun binding(type: String) = presenter.methods.single { it.returnType == "V" &&
                    it.parameterTypes.map(Any::toString) == listOf(type) }
            val vod = binding("Ltv/twitch/android/models/videos/VodModel;")
            val clip = binding("Ltv/twitch/android/models/clips/ClipModel;")
            val attach = binding("Landroid/view/ViewGroup;")
            val attachCode = attach.instructions.toList()
            val constructor = attachCode.indices.single { i ->
                val ref = attachCode[i].getReference<MethodReference>()
                attachCode[i].opcode == Opcode.INVOKE_DIRECT && ref?.name == "<init>" &&
                    ref.parameterTypes.map(Any::toString) == listOf("Landroid/content/Context;", "Landroid/view/View;")
            }
            val box = (attachCode[constructor] as FiveRegisterInstruction).registerE
            val delegate = mutableClassDefBy(attachCode[constructor].getReference<MethodReference>()!!.definingClass)
            val render = delegate.methods.single { it.returnType == "V" && it.parameterTypes.map(Any::toString) ==
                    listOf("Ltv/twitch/android/core/mvp/viewdelegate/ViewDelegateState;") }
            val rc = render.instructions.toList()
            val visibility = rc.indices.single { i ->
                val ref = rc[i].getReference<MethodReference>()
                i > 0 && rc[i].opcode == Opcode.INVOKE_STATIC &&
                    ref?.parameterTypes?.map(Any::toString) == listOf("Landroid/view/View;", "Z") &&
                    rc[i-1].getReference<FieldReference>()?.type == "Landroid/view/View;" &&
                    (maxOf(0, i-10) until i).any { rc[it].getReference<FieldReference>()?.name == "REPLAY" }
            }
            val show = rc[visibility] as FiveRegisterInstruction

            // The seekable reducer pairs Replay with ShowControls, which opens the channel panel in PiP (#108).
            val handler = replayActionHandler.method.instructions.toList()
            val analytics = handler.indexOfFirst { it.getReference<StringReference>()?.string == "replay_button" }
            val replayAction = handler.take(analytics).last { it.opcode == Opcode.INSTANCE_OF }.getReference<TypeReference>()!!.type
            val ctor = seekableControls.method.instructions.toList()
            val signature = ctor.indexOfFirst { it.getReference<StringReference>()?.string?.startsWith("processStateUpdate(") == true }
            val reducerType = ctor.drop(signature + 1).first { it.getReference<MethodReference>()?.name == "<init>" }
                .getReference<MethodReference>()!!.definingClass
            val reducer = mutableClassDefBy(reducerType).methods.single { method ->
                method.implementation?.instructions?.any { it.opcode == Opcode.SGET_OBJECT &&
                    it.getReference<FieldReference>()?.type == replayAction } == true
            }
            val states = reducer.instructions.toList()
            val replay = states.indices.single { states[it].opcode == Opcode.SGET_OBJECT &&
                states[it].getReference<FieldReference>()?.type == replayAction }
            require(states[replay + 1].opcode == Opcode.APUT_OBJECT && states[replay + 2].opcode == Opcode.APUT_OBJECT &&
                states[replay + 3].getReference<MethodReference>()?.returnType == "Ljava/util/List;" &&
                states[replay + 4].opcode == Opcode.MOVE_RESULT_OBJECT &&
                states[replay + 5].getReference<MethodReference>()?.definingClass == "Ltv/twitch/android/core/mvp/presenter/StateMachineKt;")
            val actions = (states[replay + 4] as OneRegisterInstruction).registerA

            val pip = pipActionsFingerprint.method
            val pc = pip.instructions.toList()
            val setter = pc.indices.single { pc[it].getReference<MethodReference>()?.toString() ==
                    "Landroid/app/PictureInPictureParams\$Builder;->setActions(Ljava/util/List;)Landroid/app/PictureInPictureParams\$Builder;" }
            val list = (pc[setter] as FiveRegisterInstruction).registerD
            val fieldLoad = pc.single { it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.definingClass == pip.definingClass }
            val host = (fieldLoad as TwoRegisterInstruction).registerA
            // The same local held the Activity for RemoteAction construction; no use after setActions.
            require(host != list && host != (pc[setter] as FiveRegisterInstruction).registerC)
            require(vod.implementation!!.registerCount in 3..16 && clip.implementation!!.registerCount in 3..16 &&
                    attach.implementation!!.registerCount <= 16)
            for ((method, flag) in listOf(vod to 1, clip to 0)) {
                method.addInstructions(0, "const/4 v0, $flag\ninvoke-static {p0, v0}, ${REPLAY}->bind(Ljava/lang/Object;Z)V")
            }
            attach.addInstructions(constructor+1, "invoke-static {p0, v$box}, ${REPLAY}->attach(Ljava/lang/Object;Landroid/view/View;)V")
            render.addInstructions(visibility+1, "invoke-static {v${show.registerC}, v${show.registerD}}, ${REPLAY}->state(Landroid/view/View;Z)V")
            reducer.addInstructions(replay + 5, """
                invoke-static/range {v$actions .. v$actions}, ${REPLAY}->replayActions(Ljava/util/List;)Ljava/util/List;
                move-result-object v$actions
            """.trimIndent())
            pip.addInstructions(setter, """
                iget-object v$host, p0, ${fieldLoad.getReference<FieldReference>()}
                invoke-static {v$list, v$host}, ${REPLAY}->actions(Ljava/util/List;Landroid/app/Activity;)Ljava/util/List;
                move-result-object v$list
            """.trimIndent())
            println("[ReTwitch] PiP replay: ${presenter.type}, ${delegate.type}, ${pip.definingClass}")
        } catch (e: Exception) {
            println("[ReTwitch] PiP replay skipped: $e")
        }
    }
}
