package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.ResourceType
import app.morphe.patches.all.misc.resources.getResourceId
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SYSTEM_PALETTE = "Ltv/retwitch/mod/SystemPalette;"

/** The primitive ramp also paints branding and overlays. Follow it to the two semantic theme constructors. */
val retwitchSystemPalettePatch = bytecodePatch(
    name = "ReTwitch system palette",
    description = "Applies Android 12 wallpaper colours to native resources and the light and dark Compose themes.",
) {
    compatibleWith(twitchCompatibility)
    dependsOn(retwitchExtensionPatch, retwitchSystemPaletteResources)
    execute {
        val sale = getResourceId(ResourceType.ID, "terms_of_sale_text")
        val terms = getResourceId(ResourceType.ID, "terms_of_service")
        val legal = getResourceId(ResourceType.ID, "legal_text")
        val termsIds = setOf(sale, terms, legal)
        val loading = getResourceId(ResourceType.ID, "loading_indicator")
        val grey = getResourceId(ResourceType.COLOR, "hinted_grey_1")
        val black = getResourceId(ResourceType.COLOR, "black")
        val legalOwners = mutableListOf<String>()
        classDefForEach { cls ->
            if (cls.methods.any { m -> m.name == "<init>" && m.implementation?.instructions?.any {
                    val literal = (it as? WideLiteralInstruction)?.wideLiteral
                    literal in termsIds
                } == true }) legalOwners += cls.type
        }
        var controls = 0
        for (owner in legalOwners) {
            for (method in mutableClassDefBy(owner).methods.filter { it.implementation != null }) {
                val code = method.instructions.toList()
                if (code.none { (it as? WideLiteralInstruction)?.wideLiteral in termsIds }) continue
                for (i in (0 until code.size - 2).reversed()) {
                    val literal = (code[i] as? WideLiteralInstruction)?.wideLiteral
                    if (literal !in termsIds && literal != loading) continue
                    val call = (code[i + 1] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    // Generated view binding's findChildViewById(int, View) and Android's View.findViewById(int).
                    if (call.returnType != "Landroid/view/View;" || !(call.name == "findViewById" ||
                        call.parameterTypes.map(CharSequence::toString) == listOf("I", "Landroid/view/View;"))) continue
                    if (code[i + 2].opcode != Opcode.MOVE_RESULT_OBJECT) continue
                    val reg = (code[i + 2] as OneRegisterInstruction).registerA
                    method.addInstructions(i + 3, "invoke-static/range {v$reg .. v$reg}, $SYSTEM_PALETTE->subscriptionControl(Landroid/view/View;)V")
                    controls++
                }
            }
        }
        val webViews = mutableListOf<String>()
        val bitmapLoaders = mutableListOf<MethodReference>()
        classDefForEach { cls ->
            if (cls.methods.any { it.name == "onPageFinished" } &&
                (cls.type == "Lcom/reactnativecommunity/webview/RNCWebViewClient;" || cls.methods.any { method ->
                    method.name == "shouldInterceptRequest" && method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "/portals.html"
                    } == true
                })) webViews += cls.type
            for (method in cls.methods) {
                if (method.parameterTypes.map(CharSequence::toString) != listOf("Landroid/content/res/Resources;", "I")) continue
                val calls = method.implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference } ?: continue
                if (calls.any { it.definingClass == "Landroid/graphics/drawable/BitmapDrawable;" && it.name == "getBitmap" } &&
                    calls.any { it.definingClass == "Landroid/content/res/Resources;" && it.name == "getDrawable" && it.parameterTypes.size == 2 })
                    bitmapLoaders += method
            }
        }
        for (type in webViews) {
            val callback = mutableClassDefBy(type).methods.single { it.name == "onPageFinished" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/webkit/WebView;", "Ljava/lang/String;") }
            callback.addInstructions(0, "invoke-static/range {p1 .. p2}, Ltv/retwitch/mod/PortalPalette;->apply(Landroid/webkit/WebView;Ljava/lang/String;)V")
        }
        for (ref in bitmapLoaders) {
            val method = mutableClassDefBy(ref.definingClass).methods.single { it.name == ref.name && it.parameterTypes == ref.parameterTypes }
            val i = method.instructions.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                call.definingClass == "Landroid/content/res/Resources;" && call.name == "getDrawable" && call.parameterTypes.size == 2
            } == true }
            val call = method.instructions.elementAt(i) as FiveRegisterInstruction
            method.replaceInstruction(i, "invoke-static {v${call.registerC}, v${call.registerD}, v${call.registerE}}, $SYSTEM_PALETTE->clipArt(Landroid/content/res/Resources;ILandroid/content/res/Resources\$Theme;)Landroid/graphics/drawable/Drawable;")
        }
        println("[ReTwitch] system palette: ${webViews.size} portal clients, ${bitmapLoaders.size} raster loaders")
        var bars = 0
        val landing = mutableClassDefBy("Ltv/twitch/android/feature/viewer/landing/ViewerLandingActivity;")
        for (method in landing.methods) {
            val code = method.implementation?.instructions?.toList() ?: continue
            for (i in (0 until code.size - 2).reversed()) {
                val call = (code[i] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                val paint = (code[i + 2] as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                if (call.name != "getColor" || call.returnType != "I" || code[i + 1].opcode != Opcode.MOVE_RESULT) continue
                val literal = (i - 3).coerceAtLeast(0) until i
                val matches = when (paint.name) {
                    "setBackgroundColor" -> literal.any { (code[it] as? WideLiteralInstruction)?.wideLiteral == grey }
                    "setStatusBarColor" -> literal.any { (code[it] as? WideLiteralInstruction)?.wideLiteral == black }
                    else -> false
                }
                if (!matches) continue
                val reg = (code[i + 1] as OneRegisterInstruction).registerA
                method.addInstructions(i + 2, "invoke-static/range {v$reg .. v$reg}, $SYSTEM_PALETTE->landingBar(I)I\nmove-result v$reg")
                bars++
            }
        }
        println("[ReTwitch] wallpaper colours: $controls subscription controls, $bars landing bar writes")
        try {
            val swatches = setOf(0xff9147ffL, 0xff18181bL, 0xffefeff1L, 0xfff7262dL)
            val ramps = mutableListOf<String>()
            classDefForEach { c ->
                if (c.staticFields.count { it.type == "J" } > 60 &&
                    c.methods.singleOrNull { it.name == "<clinit>" }?.implementation?.instructions?.mapNotNull {
                        (it as? WideLiteralInstruction)?.wideLiteral
                    }?.toSet()?.containsAll(swatches) == true
                ) ramps += c.type
            }
            val ramp = ramps.single()
            val constructors = mutableSetOf<MethodReference>()
            classDefForEach { c ->
                val body = c.methods.singleOrNull { it.name == "<clinit>" }?.implementation?.instructions
                if (body != null && body.count {
                        it.opcode == Opcode.SGET_WIDE && ((it as ReferenceInstruction).reference as FieldReference).definingClass == ramp
                    } >= 10) {
                    for (ins in body) {
                        val ref = (ins as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                        if (ref.name == "<init>" && ref.parameterTypes.size >= 20 && ref.parameterTypes.all { it.toString() == "J" }) {
                            constructors += ref
                        }
                    }
                }
            }
            // 31.x has the legacy semantic theme (26 colours) and the current theme (42), each in three variants.
            check(constructors.map { it.parameterTypes.size }.sorted() == listOf(26, 42))
            val plans = constructors.map { ref ->
                val ctor = mutableClassDefBy(ref.definingClass).methods.single { it.name == "<init>" &&
                    it.parameterTypes.map(CharSequence::toString) == ref.parameterTypes.map(CharSequence::toString) }
                val code = ctor.instructions.toList()
                val writes = code.filter { it.opcode == Opcode.IPUT_WIDE }
                val fields = writes.map { (it as ReferenceInstruction).reference as FieldReference }
                // In these straight-line constructors p0 is the receiver through the last field store and return.
                check(ctor.implementation!!.registerCount == 1 + 2 * ref.parameterTypes.size &&
                    code.last().opcode == Opcode.RETURN_VOID && code[code.lastIndex - 1] == writes.last() &&
                    fields.size == ref.parameterTypes.size && fields.distinct().size == fields.size &&
                    fields.all { it.definingClass == ref.definingClass && it.type == "J" } &&
                    writes.all { (it as TwoRegisterInstruction).registerB == 0 } &&
                    writes.first() == code[1] && (writes.first() as TwoRegisterInstruction).registerA == 1)
                ctor to fields
            }
            for ((ctor, fields) in plans) {
                // The constructors have no locals. At return the fields are initialized and p1..p3 are free.
                // Keep all branches in Java: long label-bearing smali snippets can be relocated incorrectly.
                val remap = fields.joinToString("\n") { field ->
                    "iget-wide v2, v0, $field\n" +
                        "invoke-static/range {v1 .. v3}, $SYSTEM_PALETTE->composeColor(ZJ)J\n" +
                        "move-result-wide v2\niput-wide v2, v0, $field"
                }
                ctor.addInstructions(ctor.instructions.count() - 1, """
                    iget-wide v1, v0, ${fields.first()}
                    invoke-static/range {v1 .. v2}, $SYSTEM_PALETTE->composeTheme(J)Z
                    move-result v1
                    $remap
                """.trimIndent())
            }
            println("[ReTwitch] system palette: Compose theme constructors " + plans.joinToString { it.first.definingClass })
        } catch (e: Exception) {
            println("[ReTwitch] Compose system palette skipped: $e")
        }
    }
}
