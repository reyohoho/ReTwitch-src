package tv.retwitch.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
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
