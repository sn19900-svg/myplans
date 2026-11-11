package com.myprojects.cad.presentation.column

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.cad.data.export.ColumnReportPdf
import com.myprojects.cad.data.io.DocumentIO
import com.myprojects.cad.domain.model.ColumnShape
import com.myprojects.cad.domain.model.newId
import com.myprojects.cad.domain.model.withName
import com.myprojects.cad.domain.repository.CadRepository
import com.myprojects.cad.domain.structural.ColumnDesign
import com.myprojects.cad.domain.structural.ColumnDesigner
import com.myprojects.cad.domain.structural.ColumnInput
import com.myprojects.cad.domain.structural.ColumnStudyRecord
import com.myprojects.cad.domain.structural.ColumnStudyRequest
import com.myprojects.cad.domain.structural.ColumnTies
import com.myprojects.cad.domain.structural.toSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.abs

data class ColumnForm(
    val name: String = "C1",
    val circular: Boolean = false,
    val spiral: Boolean = false,
    val b: String = "300",
    val h: String = "300",
    val height: String = "3",
    val braced: Boolean = true,
    val kx: String = "1",
    val ky: String = "1",
    val pd: String = "0",
    val pl: String = "0",
    val floors: String = "0",
    val selfWeight: Boolean = true,
    val mxD: String = "0",
    val mxL: String = "0",
    val myD: String = "0",
    val myL: String = "0",
    val m1m2x: String = "-1",
    val m1m2y: String = "-1",
    val fc: String = "25",
    val fy: String = "420",
    val fyt: String = "420",
    val cover: String = "40",
    val bar: String = "16",
    val autoBar: Boolean = true,
    val tie: String = "10",
    val count: String = "0",
    val minEcc: Boolean = true,
    val gD: String = "1.2",
    val gL: String = "1.6"
)

data class ColumnUiState(
    val form: ColumnForm = ColumnForm(),
    val design: ColumnDesign? = null,
    val error: String? = null,
    val note: String? = null,
    val sources: List<String> = emptyList(),
    val message: String? = null,
    val busy: Boolean = false
)

private fun String.num(): Double? = replace(',', '.').trim().toDoubleOrNull()

@HiltViewModel
class ColumnStudyViewModel @Inject constructor(
    private val repository: CadRepository,
    private val documents: DocumentIO,
    private val report: ColumnReportPdf
) : ViewModel() {

    private val _state = MutableStateFlow(ColumnUiState())
    val state: StateFlow<ColumnUiState> = _state.asStateFlow()

    private var request: ColumnStudyRequest? = null
    private var lastInput: ColumnInput? = null

    fun update(transform: (ColumnForm) -> ColumnForm) {
        _state.update { it.copy(form = transform(it.form), design = null, error = null) }
    }

    /** Opens the study for the column picked in the drawing; a saved study of that column is restored. */
    fun open(req: ColumnStudyRequest) {
        request = req
        val saved = repository.columnStudies.value.firstOrNull { it.entityId == req.entityId }
        val form = if (saved != null) formFrom(saved.input, saved.name, req)
        else defaultForm(req)
        val note = buildString {
            req.note?.let { append(it) }
            if (saved != null) {
                if (isNotEmpty()) append(' ')
                append("Restored the saved study — loads include the floors above and self-weight you entered. ")
                append("Press \"Reload loads from beams\" to take the current beam reactions instead.")
            }
        }.ifEmpty { null }
        _state.value = ColumnUiState(form = form, note = note, sources = req.loadSources)
        calculate()
    }

    private fun defaultForm(req: ColumnStudyRequest): ColumnForm {
        val circular = req.shape == ColumnShape.CIRCULAR
        return ColumnForm(
            name = req.name, circular = circular,
            b = fmt(req.bMm), h = fmt(if (circular) req.bMm else req.hMm),
            pd = fmt(req.pdKn), pl = fmt(req.plKn)
        )
    }

    private fun formFrom(i: ColumnInput, name: String, req: ColumnStudyRequest): ColumnForm {
        val circular = req.shape == ColumnShape.CIRCULAR
        return ColumnForm(
            name = name, circular = circular, spiral = i.ties == ColumnTies.SPIRAL,
            // the section always follows the drawing
            b = fmt(req.bMm), h = fmt(if (circular) req.bMm else req.hMm), height = fmt(i.heightM),
            braced = i.braced, kx = fmt(i.kx), ky = fmt(i.ky),
            pd = fmt(i.pdKn), pl = fmt(i.plKn), floors = "0", selfWeight = false,
            mxD = fmt(i.mxDKnM), mxL = fmt(i.mxLKnM), myD = fmt(i.myDKnM), myL = fmt(i.myLKnM),
            m1m2x = fmt(i.m1m2x), m1m2y = fmt(i.m1m2y),
            fc = fmt(i.fcMpa), fy = fmt(i.fyMpa), fyt = fmt(i.fytMpa), cover = fmt(i.coverMm),
            bar = fmt(i.barDiaMm), autoBar = i.autoBarDia, tie = fmt(i.tieDiaMm), count = i.barCount.toString(),
            minEcc = i.minEccentricity, gD = fmt(i.deadFactor), gL = fmt(i.liveFactor)
        )
    }

    fun setShape(circular: Boolean) = update { f ->
        f.copy(circular = circular, h = if (circular) f.b else f.h, spiral = if (circular) f.spiral else false)
    }

    fun reloadFromBeams() {
        val req = request ?: return
        update { it.copy(pd = fmt(req.pdKn), pl = fmt(req.plKn), floors = "0", selfWeight = true) }
    }

    fun calculate() {
        val input = buildInput(_state.value.form) ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = runCatching { withContext(Dispatchers.Default) { ColumnDesigner.design(input) } }
            result.onSuccess { d ->
                lastInput = input
                saveRecord(input, d)
            }
            _state.update { s ->
                result.fold(
                    { d -> s.copy(busy = false, design = d) },
                    { e -> s.copy(busy = false, error = e.message ?: "Calculation failed") }
                )
            }
        }
    }

    private fun saveRecord(input: ColumnInput, d: ColumnDesign) {
        val req = request ?: return
        val name = _state.value.form.name.trim().ifEmpty { req.name }
        val existing = repository.columnStudies.value.firstOrNull { it.entityId == req.entityId }
        repository.upsertColumnStudy(
            ColumnStudyRecord(
                id = existing?.id ?: newId(), entityId = req.entityId, name = name,
                input = input, summary = d.toSummary(input)
            )
        )
        repository.update { list -> list.map { if (it.id == req.entityId && it.name != name) it.withName(name) else it } }
    }

    private fun buildInput(f: ColumnForm): ColumnInput? {
        fun fail(msg: String): ColumnInput? {
            _state.update { it.copy(error = msg) }
            return null
        }

        val b = f.b.num(); val h0 = f.h.num(); val height = f.height.num()
        val kx = f.kx.num(); val ky = f.ky.num()
        val pd = f.pd.num(); val pl = f.pl.num(); val floors = f.floors.trim().toIntOrNull()
        val mxD = f.mxD.num(); val mxL = f.mxL.num(); val myD = f.myD.num(); val myL = f.myL.num()
        val r1 = f.m1m2x.num(); val r2 = f.m1m2y.num()
        val fc = f.fc.num(); val fy = f.fy.num(); val fyt = f.fyt.num(); val cover = f.cover.num()
        val bar = f.bar.num(); val tie = f.tie.num(); val count = f.count.trim().toIntOrNull()
        val gd = f.gD.num(); val gl = f.gL.num()
        if (b == null || h0 == null || height == null || kx == null || ky == null || pd == null || pl == null ||
            floors == null || mxD == null || mxL == null || myD == null || myL == null || r1 == null || r2 == null ||
            fc == null || fy == null || fyt == null || cover == null || bar == null || tie == null || count == null ||
            gd == null || gl == null
        ) return fail("Enter a valid number in every field")
        if (floors !in 0..50) return fail("Floors above must be between 0 and 50")

        val h = if (f.circular) b else h0
        val area = if (f.circular) PI * b * b / 4.0 else b * h
        // Self-weight of this column per floor (25 kN/m³), then everything repeated for the floors above.
        val sw = if (f.selfWeight) 25.0 * (area / 1e6) * height else 0.0
        val repeat = 1 + floors
        val input = ColumnInput(
            shape = if (f.circular) ColumnShape.CIRCULAR else ColumnShape.RECTANGULAR,
            ties = if (f.spiral && f.circular) ColumnTies.SPIRAL else ColumnTies.TIED,
            bMm = b, hMm = h, heightM = height, braced = f.braced, kx = kx, ky = ky,
            pdKn = (pd + sw) * repeat, plKn = pl * repeat,
            mxDKnM = mxD, mxLKnM = mxL, myDKnM = myD, myLKnM = myL, m1m2x = r1, m1m2y = r2,
            fcMpa = fc, fyMpa = fy, fytMpa = fyt, coverMm = cover, barDiaMm = bar, tieDiaMm = tie,
            barCount = count, minEccentricity = f.minEcc, autoBarDia = f.autoBar && count == 0,
            deadFactor = gd, liveFactor = gl
        )
        input.validate()?.let { return fail(it) }
        return input
    }

    fun exportReport(uri: Uri) {
        val s = _state.value
        val d = s.design
        val input = lastInput
        if (d == null || input == null) {
            _state.update { it.copy(message = "Run the study first") }
            return
        }
        val title = request?.projectName ?: ""
        val name = s.form.name
        val sources = s.sources
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { documents.write(uri) { out -> report.export(title, name, input, d, sources, out) } }
            }
            _state.update {
                it.copy(message = result.fold({ "Report saved" }, { e -> "Report failed: ${e.message ?: e.javaClass.simpleName}" }))
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun fmt(v: Double): String =
        if (abs(v - Math.rint(v)) < 1e-9) Math.rint(v).toLong().toString()
        else String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
