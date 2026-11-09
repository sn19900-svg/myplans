package com.myprojects.cad.presentation.beam

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myprojects.cad.domain.structural.BeamAnalysis
import com.myprojects.cad.domain.structural.BeamDesign
import com.myprojects.cad.domain.structural.BeamType
import java.util.Locale
import kotlin.math.max

private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeamStudyScreen(
    onBack: () -> Unit,
    initialSpans: List<Double>,
    vm: BeamStudyViewModel = hiltViewModel()
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    LaunchedEffect(initialSpans) { vm.prefill(initialSpans) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Beam study") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FormSection(ui.form, vm)
            Button(onClick = vm::calculate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) "Calculating…" else "Analyze & design")
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val a = ui.analysis
            val d = ui.design
            if (a != null && d != null) ResultsSection(a, d)
        }
    }
}

// ---- Input form -------------------------------------------------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

@Composable
private fun FormSection(f: BeamForm, vm: BeamStudyViewModel) {
    SectionCard("Beam type") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeamType.values().forEach { t ->
                FilterChip(selected = f.type == t, onClick = { vm.setType(t) }, label = { Text(t.label) })
            }
        }
    }

    SectionCard("Spans (m) and loads (kN/m, without self-weight)") {
        f.spans.forEachIndexed { i, s ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (f.type == BeamType.CANTILEVER) "L" else "S${i + 1}", Modifier.width(26.dp))
                NumField("Length", s.length, { vm.updateSpan(i, s.copy(length = it)) }, Modifier.weight(1f))
                NumField("Dead", s.dead, { vm.updateSpan(i, s.copy(dead = it)) }, Modifier.weight(1f))
                NumField("Live", s.live, { vm.updateSpan(i, s.copy(live = it)) }, Modifier.weight(1f))
            }
        }
        if (f.type == BeamType.CONTINUOUS) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::addSpan, enabled = f.spans.size < 8) { Text("+ span") }
                OutlinedButton(onClick = vm::removeSpan, enabled = f.spans.size > 2) { Text("− span") }
            }
        }
    }

    SectionCard("Point loads (kN) — span number, distance from the span's left end") {
        f.points.forEachIndexed { i, p ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NumField("Span", p.span, { vm.updatePoint(i, p.copy(span = it)) }, Modifier.weight(1f))
                NumField("x (m)", p.pos, { vm.updatePoint(i, p.copy(pos = it)) }, Modifier.weight(1f))
                NumField("Dead", p.dead, { vm.updatePoint(i, p.copy(dead = it)) }, Modifier.weight(1f))
                NumField("Live", p.live, { vm.updatePoint(i, p.copy(live = it)) }, Modifier.weight(1f))
                TextButton(onClick = { vm.removePoint(i) }) { Text("✕") }
            }
        }
        OutlinedButton(onClick = vm::addPoint) { Text("+ point load") }
    }

    SectionCard("Section & materials (mm, MPa)") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("b", f.b, { v -> vm.update { it.copy(b = v) } }, Modifier.weight(1f))
            NumField("h", f.h, { v -> vm.update { it.copy(h = v) } }, Modifier.weight(1f))
            NumField("Cover", f.cover, { v -> vm.update { it.copy(cover = v) } }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("f'c", f.fc, { v -> vm.update { it.copy(fc = v) } }, Modifier.weight(1f))
            NumField("fy", f.fy, { v -> vm.update { it.copy(fy = v) } }, Modifier.weight(1f))
            NumField("fyt", f.fyt, { v -> vm.update { it.copy(fyt = v) } }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Bar Φ", f.bar, { v -> vm.update { it.copy(bar = v) } }, Modifier.weight(1f))
            NumField("Stirrup Φ", f.stirrup, { v -> vm.update { it.copy(stirrup = v) } }, Modifier.weight(1f))
            NumField("Support w", f.support, { v -> vm.update { it.copy(support = v) } }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField("γ dead", f.gD, { v -> vm.update { it.copy(gD = v) } }, Modifier.weight(1f))
            NumField("γ live", f.gL, { v -> vm.update { it.copy(gL = v) } }, Modifier.weight(1f))
            Checkbox(checked = f.selfWeight, onCheckedChange = { v -> vm.update { it.copy(selfWeight = v) } })
            Text("Self-weight", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "Defaults follow ACI 318-19 (1.2D + 1.6L). Change γ to use another code's factors.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- Results -------------------------------------------------------------------------------------------

@Composable
private fun Line(text: String, bold: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else null)
}

@Composable
private fun SimpleTable(headers: List<String>, rows: List<List<String>>) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            headers.forEach {
                Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                r.forEach { Text(it, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun ResultsSection(a: BeamAnalysis, d: BeamDesign) {
    val inp = a.input
    val warn = MaterialTheme.colorScheme.error

    SectionCard("Analysis summary — ${f1(inp.deadFactor)}D + ${f1(inp.liveFactor)}L with live-load pattern loading") {
        Line("Self-weight ${f2(inp.selfWeightKnPerM)} kN/m · Ec = ${f0(a.ecMpa)} MPa · EI = ${f0(a.eiKnM2)} kN·m² (gross)")
        a.reactions.forEach { r ->
            Line("Support ${r.label} (x = ${f2(r.xM)} m): R dead = ${f1(r.deadKn)} kN, R live = ${f1(r.liveKn)} kN, R ultimate max = ${f1(r.ultimateMaxKn)} kN")
        }
        a.spanPositiveKnM.forEachIndexed { j, m ->
            if (m > 0.01) Line("Span ${j + 1}: Mu+ = ${f1(m)} kN·m at x = ${f2(a.spanPositiveAtX[j])} m")
        }
        a.supportNegativeKnM.forEachIndexed { i, m ->
            if (m > 0.01) Line("Support ${'A' + i}: Mu− = ${f1(m)} kN·m")
        }
        Line("Vu max = ${f1(max(a.vMax.max(), -a.vMin.min()))} kN", bold = true)
    }

    SectionCard("Diagrams (ultimate envelope)") {
        DiagramPlot("Shear force Vu (kN)", a.xs, listOf(a.vMax, a.vMin), a.supportsX, "kN", positiveDown = false)
        DiagramPlot("Bending moment Mu (kN·m) — sagging drawn down", a.xs, listOf(a.mMax, a.mMin), a.supportsX, "kN·m", positiveDown = true)
        val defl = DoubleArray(a.deflXs.size) { (a.deflDeadMm[it] + a.deflLiveMm[it]) * d.ieScale }
        DiagramPlot("Service deflection D+L, cracked section (mm)", a.deflXs, listOf(defl), a.supportsX, "mm", positiveDown = true)
    }

    SectionCard("Flexure design (ACI 318-19)") {
        SimpleTable(
            listOf("Section", "Mu", "As req", "As prov", "Bars", "φMn"),
            d.flexure.map {
                listOf(
                    it.label, f1(it.muKnM),
                    if (it.asReqMm2.isFinite()) f0(it.asReqMm2) else "—",
                    f0(it.asProvMm2), it.barsText, f1(it.phiMnKnM) + if (it.ok) "" else " ✗"
                )
            }
        )
        Line("Mu in kN·m, As in mm²; As,min governs where As req is smaller.")
        d.flexure.filter { it.note != null }.forEach {
            Text("• ${it.label}: ${it.note}", style = MaterialTheme.typography.bodySmall, color = if (it.ok) MaterialTheme.colorScheme.onSurface else warn)
        }
    }

    SectionCard("Shear design — critical section d from the support face") {
        d.shear.forEach {
            Line("${it.label}: Vu = ${f1(it.vuKn)} kN, φVc = ${f1(it.phiVcKn)} kN (d = ${f0(it.dMm)} mm)", bold = true)
            Text(it.stirrupText, style = MaterialTheme.typography.bodySmall, color = if (it.ok) MaterialTheme.colorScheme.onSurface else warn)
            it.note?.let { n -> Text("• $n", style = MaterialTheme.typography.bodySmall, color = if (it.ok) MaterialTheme.colorScheme.onSurface else warn) }
        }
    }

    SectionCard("Deflection (ACI Table 24.2.2)") {
        SimpleTable(
            listOf("Span", "Live", "After attach.", "L/360", "L/480", "L/240"),
            d.deflection.map {
                listOf(it.label, f1(it.liveMm), f1(it.afterAttachmentMm), f1(it.liveLimitMm), f1(it.limit480Mm), f1(it.limit240Mm))
            }
        )
        d.deflection.forEach {
            Text("• ${it.label}: ${it.status}", style = MaterialTheme.typography.bodySmall, color = if (it.ok) MaterialTheme.colorScheme.onSurface else warn)
        }
        Line(d.deflectionNote)
        d.hMin.forEach {
            Line("Min. depth without deflection calc (Table 9.3.1.1), ${it.label}: ${f0(it.hMinMm)} mm — ${if (it.ok) "satisfied" else "NOT satisfied"}")
        }
    }

    SectionCard(if (d.warnings.isEmpty()) "Result: all checks passed" else "Result: ${d.warnings.size} check(s) failed") {
        d.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = warn) }
        Text(
            "Preliminary design aid. Singly reinforced rectangular sections, gravity loads only, supports assumed to compress the beam ends. " +
                "Torsion, seismic detailing, development/splice lengths, and cut-off points are not covered. " +
                "Results must be checked and approved by a licensed engineer before use.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
