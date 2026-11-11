package com.myprojects.cad.presentation.column

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myprojects.cad.domain.structural.ColumnDesign
import com.myprojects.cad.domain.structural.ColumnStudyRequest
import java.util.Locale

private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
private fun f2(v: Double) = String.format(Locale.US, "%.2f", v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumnStudyScreen(
    request: ColumnStudyRequest,
    onBack: () -> Unit,
    vm: ColumnStudyViewModel = hiltViewModel()
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    BackHandler(onBack = onBack)
    LaunchedEffect(request) { vm.open(request) }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    val reportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) vm.exportReport(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Column ${ui.form.name}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    TextButton(
                        onClick = { reportLauncher.launch("${ui.form.name.ifBlank { "column" }}-calc.pdf") },
                        enabled = ui.design != null
                    ) { Text("PDF") }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ui.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            ColumnFormSection(ui.form, ui.sources, vm)
            Button(onClick = vm::calculate, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.busy) "Calculating…" else "Analyze & design")
            }
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            ui.design?.let { Results(it) }
        }
    }
}

// ---- Form -----------------------------------------------------------------------------------------------

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
private fun ColumnFormSection(f: ColumnForm, sources: List<String>, vm: ColumnStudyViewModel) {
    SectionCard("Column mark, shape and section") {
        OutlinedTextField(
            value = f.name, onValueChange = { v -> vm.update { it.copy(name = v) } },
            singleLine = true, label = { Text("Mark (e.g. C1)") }, modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !f.circular, onClick = { vm.setShape(false) }, label = { Text("Rectangular / square") })
            FilterChip(selected = f.circular, onClick = { vm.setShape(true) }, label = { Text("Circular") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !f.spiral, onClick = { vm.update { it.copy(spiral = false) } }, label = { Text("Tied") })
            FilterChip(
                selected = f.spiral && f.circular,
                onClick = { vm.update { it.copy(spiral = it.circular) } },
                enabled = f.circular,
                label = { Text("Spiral (circular only)") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (f.circular) {
                NumField("Diameter (mm)", f.b, { v -> vm.update { it.copy(b = v, h = v) } }, Modifier.weight(1f))
            } else {
                NumField("b (mm)", f.b, { v -> vm.update { it.copy(b = v) } }, Modifier.weight(1f))
                NumField("h (mm)", f.h, { v -> vm.update { it.copy(h = v) } }, Modifier.weight(1f))
            }
            NumField("Height lu (m)", f.height, { v -> vm.update { it.copy(height = v) } }, Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = f.braced, onClick = { vm.update { it.copy(braced = true) } }, label = { Text("Braced (non-sway)") })
            FilterChip(
                selected = !f.braced,
                onClick = { vm.update { it.copy(braced = false, kx = maxOf(it.kx.toDoubleOrNull() ?: 1.0, 1.0).toString(), ky = maxOf(it.ky.toDoubleOrNull() ?: 1.0, 1.0).toString()) } },
                label = { Text("Sway frame") }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("k about X", f.kx, { v -> vm.update { it.copy(kx = v) } }, Modifier.weight(1f))
            NumField("k about Y", f.ky, { v -> vm.update { it.copy(ky = v) } }, Modifier.weight(1f))
        }
    }

    SectionCard("Axial loads (kN, service)") {
        if (sources.isNotEmpty()) {
            Text("From the beams resting on this column:", style = MaterialTheme.typography.labelMedium)
            sources.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("PD (dead)", f.pd, { v -> vm.update { it.copy(pd = v) } }, Modifier.weight(1f))
            NumField("PL (live)", f.pl, { v -> vm.update { it.copy(pl = v) } }, Modifier.weight(1f))
            NumField("Floors above", f.floors, { v -> vm.update { it.copy(floors = v) } }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = f.selfWeight, onCheckedChange = { v -> vm.update { it.copy(selfWeight = v) } })
            Text("Add this column's self-weight", style = MaterialTheme.typography.labelMedium)
        }
        Text(
            "Floors above repeat PD, PL and the self-weight, as for identical storeys (no live-load reduction).",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = vm::reloadFromBeams) { Text("Reload loads from beams") }
    }

    SectionCard("Moments at the critical end (kN·m, service)") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Mx dead", f.mxD, { v -> vm.update { it.copy(mxD = v) } }, Modifier.weight(1f))
            NumField("Mx live", f.mxL, { v -> vm.update { it.copy(mxL = v) } }, Modifier.weight(1f))
            NumField("M1/M2 x", f.m1m2x, { v -> vm.update { it.copy(m1m2x = v) } }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("My dead", f.myD, { v -> vm.update { it.copy(myD = v) } }, Modifier.weight(1f))
            NumField("My live", f.myL, { v -> vm.update { it.copy(myL = v) } }, Modifier.weight(1f))
            NumField("M1/M2 y", f.m1m2y, { v -> vm.update { it.copy(m1m2y = v) } }, Modifier.weight(1f))
        }
        Text(
            "Mx bends the section over its depth h, My over its width b. M1/M2: −1 = single curvature (worst case), +1 = double curvature.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = f.minEcc, onCheckedChange = { v -> vm.update { it.copy(minEcc = v) } })
            Text("Apply minimum eccentricity 15 + 0.03h", style = MaterialTheme.typography.labelMedium)
        }
    }

    SectionCard("Materials and reinforcement") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("f'c", f.fc, { v -> vm.update { it.copy(fc = v) } }, Modifier.weight(1f))
            NumField("fy", f.fy, { v -> vm.update { it.copy(fy = v) } }, Modifier.weight(1f))
            NumField("fyt", f.fyt, { v -> vm.update { it.copy(fyt = v) } }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("Cover", f.cover, { v -> vm.update { it.copy(cover = v) } }, Modifier.weight(1f))
            NumField("Bar Φ", f.bar, { v -> vm.update { it.copy(bar = v) } }, Modifier.weight(1f))
            NumField("Tie Φ", f.tie, { v -> vm.update { it.copy(tie = v) } }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            NumField("Bars (0 = auto)", f.count, { v -> vm.update { it.copy(count = v) } }, Modifier.weight(1f))
            Checkbox(checked = f.autoBar, onCheckedChange = { v -> vm.update { it.copy(autoBar = v) } })
            Text("Auto Φ", style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NumField("γ dead", f.gD, { v -> vm.update { it.copy(gD = v) } }, Modifier.weight(1f))
            NumField("γ live", f.gL, { v -> vm.update { it.copy(gL = v) } }, Modifier.weight(1f))
        }
        Text(
            "Auto Φ tries Φ14–Φ32 and keeps the lightest design (used only when the bar count is 0).",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ---- Results ---------------------------------------------------------------------------------------------

@Composable
private fun Line(text: String, bold: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else null)
}

@Composable
private fun Results(d: ColumnDesign) {
    val warn = MaterialTheme.colorScheme.error
    SectionCard(if (d.ok) "Result: OK — ratio ${f2(d.ratio)}" else "Result: NOT OK — ratio ${f2(d.ratio)}") {
        Line(d.typeLabel, bold = true)
        Line("Pu = ${f1(d.puKn)} kN · design moments Mux = ${f1(d.muxKnM)}, Muy = ${f1(d.muyKnM)} kN·m")
        Line("Longitudinal: ${d.arrangement}   As = ${f0(d.asMm2)} mm²   ρ = ${f2(d.rho * 100)} %", bold = true)
        Line(d.tiesText)
        Line("φPn,max = ${f0(d.phiPnMaxKn)} kN" +
            (d.phiMnxKnM?.let { " · φMnx at Pu = ${f1(it)} kN·m" } ?: "") +
            (d.phiMnyKnM?.let { " · φMny at Pu = ${f1(it)} kN·m" } ?: ""))
        Line("Method: ${d.method}")
    }

    SectionCard("Slenderness (ACI 6.2.5 / 6.6.4)") {
        listOf(d.axisX, d.axisY).forEach {
            Line(it.axis.substringBefore(' ') + ": kℓu/r = ${f1(it.kluOverR)} (limit ${f1(it.limit)}) — " +
                if (it.slender) "slender" else "short", bold = true)
            if (it.slender) {
                Line("   Pc = ${if (it.pcKn.isFinite()) f0(it.pcKn) else "—"} kN, Cm = ${f2(it.cm)}, δ = ${if (it.delta.isFinite()) f2(it.delta) else "∞"}, " +
                    "M2 = ${f1(it.m2KnM)} → Mc = ${if (it.designMomentKnM.isFinite()) f1(it.designMomentKnM) else "∞"} kN·m")
            }
        }
    }

    SectionCard("Interaction diagram") {
        InteractionPlot("φPn – φMn design strength", d.curveX, d.curveY, d.demandX, d.demandY)
    }

    SectionCard(if (d.warnings.isEmpty()) "Checks" else "${d.warnings.size} problem(s)") {
        d.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = warn) }
        d.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        Text(
            "Preliminary design aid for gravity-loaded columns. Column shear, seismic detailing, joint design, splices and the " +
                "footing are not covered. Results must be checked and approved by a licensed engineer before use.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
