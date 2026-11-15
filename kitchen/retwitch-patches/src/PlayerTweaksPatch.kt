package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val CORE_PLAYER = "Lcom/amazonaws/ivs/player/CorePlayerImpl;"
private const val IVS_MEDIA_PLAYER = "Lcom/amazonaws/ivs/player/MediaPlayer;"
private const val PLAYER_STATE = "Lcom/amazonaws/ivs/player/Player\$State;"

/**
 * Player tweaks (default quality per network, auto-reload on stall, fullscreen
 * gestures). Hooks the IVS SDK's own state dispatch --
 * `CorePlayerImpl.lambda$handleStateChange$N(int)`, which maps the native state
 * to `Player.State` and notifies every listener on the main thread -- right
 * after `mapState(...)`, handing the player (`this`) and the state to
 * `PlayerHook`. IVS SDK names are not R8-renamed. Also installs
 * `PlayerGestures` and `UiHider` (hide buttons by view-id name) from
 * `TwitchApplication.onCreate`.
 */
val retwitchPlayerTweaksPatch = bytecodePatch(
    name = "ReTwitch player tweaks",
    description = "Default quality for Wi-Fi/cellular, auto-reload of stalled streams, fullscreen brightness/volume gestures and the audio compressor.",
) {
    compatibleWith(twitchCompatibility)

    dependsOn(retwitchExtensionPatch)

    execute {
        val core = mutableClassDefByOrNull { it.type == CORE_PLAYER }
            ?: throw PatchException("$CORE_PLAYER not found")
        val dispatch = core.methods.firstOrNull { m ->
            m.name.startsWith("lambda\$handleStateChange\$") &&
                m.parameterTypes.map { it.toString() } == listOf("I")
        } ?: throw PatchException("IVS state dispatch lambda not found")
        val insns = dispatch.instructions.toList()
        val mapIndex = insns.indexOfFirst {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                ref.name == "mapState" && ref.returnType == PLAYER_STATE
            } == true
        }
        if (mapIndex < 0 || insns[mapIndex + 1].opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException("mapState call not found in ${dispatch.name}")
        }
        val stateReg = (insns[mapIndex + 1] as OneRegisterInstruction).registerA
        dispatch.addInstructions(
            mapIndex + 2,
            "invoke-static {p0, v$stateReg}, Ltv/retwitch/mod/PlayerHook;->onStateChanged(Ljava/lang/Object;Ljava/lang/Object;)V",
        )

        // Playback commands can arrive before the corresponding state callback.
        try {
            val playbackCommands = listOf("pause" to "onPause", "play" to "onPlay").map { (command, hook) ->
                val method = core.methods.single {
                    it.name == command && it.parameterTypes.isEmpty() && it.returnType == "V" &&
                        !AccessFlags.NATIVE.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
                }
                val nativeCall = method.instructions.indexOfFirst { insn ->
                    if (insn.opcode != Opcode.INVOKE_DIRECT && insn.opcode != Opcode.INVOKE_DIRECT_RANGE) {
                        false
                    } else {
                        val ref = (insn as ReferenceInstruction).reference as MethodReference
                        ref.definingClass == CORE_PLAYER && ref.name == command && ref.returnType == "V" &&
                            ref.parameterTypes.map { it.toString() } == listOf("J")
                    }
                }
                if (nativeCall < 0) throw PatchException("native $command call not found")
                Triple(method, nativeCall + 1, hook)
            }
            playbackCommands.forEach { (method, index, hook) ->
                method.addInstructions(
                    index,
                    "invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/LiveScreenOn;->$hook(Ljava/lang/Object;)V",
                )
            }
            println("[ReTwitch] player tweaks: screen timeout playback commands hooked")
        } catch (e: Exception) {
            println("[ReTwitch] player tweaks: screen timeout playback commands skipped: $e")
        }

        // The standard theatre mounts a playback bridge directly in its layout.
        // Match the Twitch wrapper by the SDK field and where it stores that bridge and reads its surface.
        try {
            val candidates = mutableListOf<MethodReference>()
            classDefForEach { owner ->
                if (owner.fields.none { it.type == IVS_MEDIA_PLAYER }) return@classDefForEach
                for (method in owner.methods) {
                    val bridge = method.parameterTypes.singleOrNull()?.toString() ?: continue
                    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) continue
                    val code = method.implementation?.instructions?.toList() ?: continue
                    val storesBridge = code.any { insn ->
                        insn.opcode == Opcode.IPUT_OBJECT &&
                            ((insn as ReferenceInstruction).reference as FieldReference).let { ref ->
                                ref.definingClass == owner.type && ref.type == bridge
                            }
                    }
                    if (!storesBridge) continue
                    val readsSurface = code.any { insn ->
                        (insn.opcode == Opcode.INVOKE_INTERFACE || insn.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
                            ((insn as ReferenceInstruction).reference as MethodReference).let { ref ->
                                ref.definingClass == bridge && ref.name == "getSurface" && ref.parameterTypes.isEmpty() &&
                                    ref.returnType == "Landroid/view/Surface;"
                            }
                    }
                    if (readsSurface) candidates.add(method)
                }
            }
            val candidate = candidates.single()
            val bridge = candidate.parameterTypes.single().toString()
            val viewMethod = mutableClassDefBy(bridge).methods.any {
                it.name == "getView" && it.parameterTypes.isEmpty() && it.returnType == "Landroid/view/View;"
            }
            if (!viewMethod) throw PatchException("playback bridge getView not found")
            val attach = mutableClassDefBy(candidate.definingClass).methods.single {
                it.name == candidate.name && it.parameterTypes.singleOrNull()?.toString() == bridge && it.returnType == "V"
            }
            attach.addInstructions(
                0,
                "invoke-static/range {p0 .. p1}, Ltv/retwitch/mod/LiveScreenOn;->bindPlaybackView(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
            println("[ReTwitch] player tweaks: screen timeout playback view hooked")
        } catch (e: Exception) {
            println("[ReTwitch] player tweaks: screen timeout playback view skipped: $e")
        }

        // Default quality: Twitch re-enables auto quality after load on its own
        // schedule; PlayerHook may apply the default instead (true = skip).
        // Both public overloads, (Z)V and (ZZ)V; v0 is a free local at entry.
        val autoModes = core.methods.filter {
            it.name == "setAutoQualityMode" && !AccessFlags.NATIVE.isSet(it.accessFlags) &&
                it.parameterTypes.firstOrNull()?.toString() == "Z"
        }
        if (autoModes.isEmpty()) throw PatchException("IVS setAutoQualityMode not found")
        autoModes.forEach { m ->
            m.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p0, p1}, Ltv/retwitch/mod/PlayerHook;->onAutoQualityMode(Ljava/lang/Object;Z)Z
                    move-result v0
                    if-eqz v0, :rt_auto_quality
                    return-void
                """.trimIndent(),
                ExternalLabel("rt_auto_quality", m.getInstruction(0)),
            )
        }

        // "Normal latency" option: every IVS load funnels into load(LoadOptions);
        // after it PlayerHook may turn low latency off, and the public setter
        // passes requests through PlayerHook.filterLowLatency. Soft: an option.
        try {
            val lowLatency = core.methods.single {
                it.name == "setLiveLowLatencyEnabled" && !AccessFlags.NATIVE.isSet(it.accessFlags) &&
                    it.parameterTypes.map { p -> p.toString() } == listOf("Z")
            }
            lowLatency.addInstructions(
                0,
                """
                    invoke-static {p1}, Ltv/retwitch/mod/PlayerHook;->filterLowLatency(Z)Z
                    move-result p1
                """.trimIndent(),
            )
            val load = core.methods.single {
                it.name == "load" && it.parameterTypes.map { p -> p.toString() } ==
                    listOf("Lcom/amazonaws/ivs/player/MediaPlayer\$LoadOptions;")
            }
            // Right after the native load call: the return-void is a branch target
            // (`if-eqz` on the config holder), which an insertion before it would miss.
            val nativeLoad = load.instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { ref ->
                    ref.definingClass == CORE_PLAYER && ref.name == "load" && ref.parameterTypes.firstOrNull()?.toString() == "J"
                } == true
            }
            if (nativeLoad < 0) throw PatchException("native load call not found")
            load.addInstructions(
                nativeLoad + 1,
                "invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/PlayerHook;->onLoad(Ljava/lang/Object;)V",
            )
        } catch (e: Exception) {
            println("[ReTwitch] player tweaks: normal latency skipped: $e")
        }

        // Player settings menu: its selected quality is Twitch's requested one
        // (31.8.0 `x720.D2(z620, UserReportModel, s5k)` reads `z620.f()`), which the
        // default quality set at the IVS level doesn't touch. Soft: the menu only.
        try {
            var site: Pair<com.android.tools.smali.dexlib2.iface.Method, Int>? = null
            classDefForEach { c ->
                for (m in c.methods) {
                    val params = m.parameterTypes.map { it.toString() }
                    if (site != null || m.returnType != "V" || params.size != 3 ||
                        params[1] != "Ltv/twitch/android/models/report/UserReportModel;"
                    ) continue
                    val code = m.implementation?.instructions?.toList() ?: continue
                    val i = code.indexOfFirst {
                        (it.opcode == Opcode.INVOKE_INTERFACE || it.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
                            ((it as ReferenceInstruction).reference as MethodReference).let { r ->
                                r.definingClass == params[0] && r.parameterTypes.isEmpty() && r.returnType == "Ljava/lang/String;"
                            }
                    }
                    if (i >= 0 && code[i + 1].opcode == Opcode.MOVE_RESULT_OBJECT) site = m to i + 1
                }
            }
            val (found, moveIndex) = site ?: throw PatchException("settings menu quality read not found")
            val menu = mutableClassDefBy(found.definingClass).methods.first {
                it.name == found.name && it.parameterTypes.map { p -> p.toString() } == found.parameterTypes.map { p -> p.toString() }
            }
            val reg = (menu.getInstruction(moveIndex) as OneRegisterInstruction).registerA
            menu.addInstructions(
                moveIndex + 1,
                """
                    invoke-static/range {v$reg .. v$reg}, Ltv/retwitch/mod/PlayerHook;->menuQuality(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$reg
                """.trimIndent(),
            )
        } catch (e: Exception) {
            println("[ReTwitch] player tweaks: settings menu quality skipped: $e")
        }

        // Audio compressor: report each IVS AudioTrackRenderer after it
        // (re)configures its AudioTrack, i.e. before every return of configure().
        val renderer = mutableClassDefByOrNull { it.type == "Lcom/amazonaws/ivs/player/AudioTrackRenderer;" }
            ?: throw PatchException("IVS AudioTrackRenderer not found")
        val configure = renderer.methods.single {
            it.name == "configure" && it.parameterTypes.map { p -> p.toString() } == listOf("Landroid/media/MediaFormat;")
        }
        configure.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            .sortedDescending().forEach { index ->
                configure.addInstructions(
                    index,
                    "invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/AudioCompressor;->onRenderer(Ljava/lang/Object;)V",
                )
            }
        // The track only starts in render(), once enough is buffered: on a slow stream well after the player reports
        // PLAYING, so the effect missed it until something else refreshed. Also when a muted player gets its volume.
        val render = renderer.methods.single { it.name == "render" }
        render.instructions.withIndex().filter { (_, insn) ->
            insn.opcode == Opcode.INVOKE_VIRTUAL && ((insn as ReferenceInstruction).reference as MethodReference).let {
                it.definingClass == "Landroid/media/AudioTrack;" && it.name == "play"
            }
        }.map { it.index }.sortedDescending().forEach { index ->
            render.addInstructions(
                index + 1,
                "invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/AudioCompressor;->onRenderer(Ljava/lang/Object;)V",
            )
        }
        val setVolume = renderer.methods.single { it.name == "setVolume" && it.parameterTypes.map { p -> p.toString() } == listOf("F") }
        setVolume.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            .sortedDescending().forEach { index ->
                setVolume.addInstructions(
                    index,
                    "invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/AudioCompressor;->onRenderer(Ljava/lang/Object;)V",
                )
            }

        val app = mutableClassDefByOrNull { it.type == "Ltv/twitch/android/app/consumer/TwitchApplication;" }
            ?: throw PatchException("TwitchApplication not found")
        app.methods.single { it.name == "onCreate" && it.parameterTypes.isEmpty() }.addInstructions(
            0,
            """
                invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/PlayerGestures;->install(Landroid/app/Application;)V
                invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/UiHider;->install(Landroid/app/Application;)V
                invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/Ambilight;->install(Landroid/app/Application;)V
                invoke-static/range {p0 .. p0}, Ltv/retwitch/mod/JailChat;->install(Landroid/app/Application;)V
            """.trimIndent(),
        )

        // VAFT log: every IVS request (segments go straight to the CDN). create() is the one factory
        // behind Platform.createHttpClient(); the mod wraps the client only while the log is on.
        try {
            val factory = mutableClassDefByOrNull { it.type == "Lcom/amazonaws/ivs/net/HttpClientFactory;" }
                ?: throw PatchException("HttpClientFactory not found")
            val create = factory.methods.single { it.name == "create" && it.parameterTypes.isEmpty() }
            create.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
                .sortedDescending().forEach { index ->
                    val reg = (create.getInstruction(index) as OneRegisterInstruction).registerA
                    create.addInstructions(
                        index,
                        """
                            invoke-static {v$reg}, Ltv/retwitch/mod/ads/IvsNetLog;->wrap(Ljava/lang/Object;)Ljava/lang/Object;
                            move-result-object v$reg
                            check-cast v$reg, Lcom/amazonaws/ivs/net/HttpClient;
                        """.trimIndent(),
                    )
                }
        } catch (e: Exception) {
            println("[ReTwitch] player tweaks: IVS request log skipped: $e")
        }
    }
}
