package com.myprojects.cad.presentation.beam

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.cad.data.export.BeamReportPdf
import com.myprojects.cad.data.io.DocumentIO
import com.myprojects.cad.domain.model.withName
import com.myprojects.cad.domain.repository.CadRepository
import com.myprojects.cad.domain.structural.BeamAnalysis
import com.myprojects.cad.domain.structural.BeamDesign
import com.myprojects.cad.domain.structural.BeamDesigner
import com.myprojects.cad.domain.structural.BeamInput
import com.myprojects.cad.domain.structural.BeamPointLoad
import com.myprojects.cad.domain.structural.BeamSolver
import com.myprojects.cad.domain.structural.BeamStudyRequest
import com.myprojects.cad.domain.structural.BeamType
import com.myprojects.cad.domain.structural.EndCondition
import com.myprojects.cad.domain.structural.StudyRecord
import com.myprojects.cad.domain.structural.SupportLoad
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
import kotlin.math.abs
import kotlin.math.max

data class SpanForm(val length: String = "5", val dead: String = "10", val live: String = "5")

data class PointForm(val span: String = "1", val pos: String = "2.5", val dead: String = "0", val live: String = "0")

data class BeamForm(
    val name: String = "B1",
    val type: BeamType = BeamType.SIMPLY_SUPPORTED,
    val leftEnd: EndCondition = EndCondition.PINNED,
    val rightEnd: EndCondition = EndCondition.PINNED,
    val spans: List<SpanForm> = listOf(SpanForm()),
    val points: List<PointForm> = emptyList(),
    val b: String = "250",
    val h: String = "500",
    val fc: String = "25",
    val fy: String = "420",
    val fyt: String = "420",
    val cover: String = "40",
    val stirrup: String = "10",
    val bar: String = "16",
    val support: String = "250",
    val selfWeight: Boolean = true,
    val gD: String = "1.2",
    val gL: String = "1.6"
)

data class BeamUiState(
    val form: BeamForm = BeamForm(),
    val analysis: BeamAnalysis? = null,
    val design: BeamDesign? = null,
    val error: String? = null,
    val note: String? = null,
    val message: String? = null,
    val busy: Boolean = false
)

private fun String.num(): Double? = replace(',', '.').trim().toDoubleOrNull()

@HiltViewModel
class BeamStudyViewModel @Inject constructor(
    private val repository: CadRepository,
    private val documents: DocumentIO,
    private val report: BeamReportPdf
) : ViewModel() {

    private val _state = MutableStateFlow(BeamUiState())
    val state: StateFlow<BeamUiState> = _state.asStateFlow()

    private var request: BeamStudyRequest? = null
    private var projectName: String = ""

    /** Any edit invalidates the previous results so stale numbers are never shown. */
    fun update(transform: (BeamForm) -> BeamForm) {
        _state.update { it.copy(form = transform(it.form), analysis = null, design = null, error = null) }
    }

    // ---- Opening a study from the drawing ---------------------------------------------------------------

    /**
     * Opens the study for the elements picked in the drawing. A previously saved study of the same elements
     * is restored (new span lengths from the drawing, loads and section from the record), then analysed at once.
     */
    fun open(req: BeamStudyRequest) {
        request = req
        projectName = req.projectName
        val saved = repository.studies.value.firstOrNull { it.entityIds.toSet() == req.entityIds.toSet() }
        val form = if (saved != null) formFrom(saved.input, req.spansM, saved.name)
        else defaultForm(req.spansM, req.name, _state.value.form)
        _state.value = BeamUiState(form = form, note = req.note)
        calculate()
    }

    private fun defaultForm(lengths: List<Double>, name: String, previous: BeamForm): BeamForm {
        val template = previous.spans.lastOrNull() ?: SpanForm()
        val type = if (lengths.size >= 2) BeamType.CONTINUOUS else {
            if (previous.type == BeamType.CONTINUOUS) BeamType.SIMPLY_SUPPORTED else previous.type
        }
        return previous.copy(
            name = name,
            type = type,
            spans = lengths.map { template.copy(length = fmt(it)) },
            points = emptyList()
        )
    }

    private fun formFrom(i: BeamInput, lengths: List<Double>, name: String): BeamForm {
        val sameCount = lengths.size == i.spansM.size
        val type = when {
            sameCount -> i.type
            lengths.size >= 2 -> BeamType.CONTINUOUS
            i.type == BeamType.CONTINUOUS -> BeamType.SIMPLY_SUPPORTED
            else -> i.type
        }
        val spans = lengths.mapIndexed { k, l ->
            val d = i.deadKnPerM.getOrElse(k) { i.deadKnPerM.lastOrNull() ?: 10.0 }
            val lv = i.liveKnPerM.getOrElse(k) { i.liveKnPerM.lastOrNull() ?: 5.0 }
            SpanForm(fmt(l), fmt(d), fmt(lv))
        }
        val points = if (sameCount) i.pointLoads.map { PointForm((it.spanIndex + 1).toString(), fmt(it.posM), fmt(it.deadKn), fmt(it.liveKn)) } else emptyList()
        return BeamForm(
            name = name, type = type, leftEnd = i.leftEnd, rightEnd = i.rightEnd,
            spans = spans, points = points,
            b = fmt(i.bMm), h = fmt(i.hMm), fc = fmt(i.fcMpa), fy = fmt(i.fyMpa), fyt = fmt(i.fytMpa),
            cover = fmt(i.coverMm), stirrup = fmt(i.stirrupDiaMm), bar = fmt(i.barDiaMm), support = fmt(i.supportWidthMm),
            selfWeight = i.includeSelfWeight, gD = fmt(i.deadFactor), gL = fmt(i.liveFactor)
        )
    }

    // ---- Form editing ------------------------------------------------------------------------------------

    fun setType(type: BeamType) = update { f ->
        val spans = when (type) {
            BeamType.CONTINUOUS -> if (f.spans.size >= 2) f.spans else f.spans + f.spans.last()
            else -> listOf(f.spans.first())
        }
        f.copy(type = type, spans = spans, points = f.points.filter { (it.span.toIntOrNull() ?: 1) <= spans.size })
    }

    fun setLeftEnd(c: EndCondition) = update { it.copy(leftEnd = c) }
    fun setRightEnd(c: EndCondition) = update { it.copy(rightEnd = c) }

    fun addSpan() = update { f -> if (f.spans.size >= 8) f else f.copy(spans = f.spans + f.spans.last()) }
    fun removeSpan() = update { f ->
        if (f.spans.size <= 2) f
        else f.copy(
            spans = f.spans.dropLast(1),
            points = f.points.filter { (it.span.toIntOrNull() ?: 1) <= f.spans.size - 1 }
        )
    }

    fun updateSpan(index: Int, span: SpanForm) = update { f ->
        f.copy(spans = f.spans.mapIndexed { i, old -> if (i == index) span else old })
    }

    fun addPoint() = update { f -> f.copy(points = f.points + PointForm()) }
    fun removePoint(index: Int) = update { f -> f.copy(points = f.points.filterIndexed { i, _ -> i != index }) }
    fun updatePoint(index: Int, p: PointForm) = update { f ->
        f.copy(points = f.points.mapIndexed { i, old -> if (i == index) p else old })
    }

    // ---- Calculation -------------------------------------------------------------------------------------

    fun calculate() {
        val input = buildInput(_state.value.form) ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val analysis = BeamSolver.analyze(input)
                    analysis to BeamDesigner.design(analysis)
                }
            }
            result.onSuccess { (a, d) -> saveRecord(input, a, d) }
            _state.update { s ->
                result.fold(
                    { (a, d) -> s.copy(busy = false, analysis = a, design = d) },
                    { e -> s.copy(busy = false, error = e.message ?: "Calculation failed") }
                )
            }
        }
    }

    /** Stores the study with the drawing and stamps the beam's mark on its elements. */
    private fun saveRecord(input: BeamInput, a: BeamAnalysis, d: BeamDesign) {
        val req = request ?: return
        val name = _state.value.form.name.trim().ifEmpty { req.name }
        val mu = max(a.mMax.max(), -a.mMin.min())
        val vu = max(a.vMax.max(), -a.vMin.min())
        val existing = repository.studies.value.firstOrNull { it.entityIds.toSet() == req.entityIds.toSet() }
        repository.upsertStudy(
            StudyRecord(
                id = existing?.id ?: com.myprojects.cad.domain.model.newId(),
                entityIds = req.entityIds,
                name = name,
                input = input,
                summary = d.toSummary(input, mu, vu),
                supports = supportLoads(input, a, req, name)
            )
        )
        val ids = req.entityIds.toSet()
        repository.update { list -> list.map { if (it.id in ids && it.name != name) it.withName(name) else it } }
    }

    /** Reactions delivered to each support, placed at the support's position in the drawing (loads for the columns below). */
    private fun supportLoads(input: BeamInput, a: BeamAnalysis, req: BeamStudyRequest, name: String): List<SupportLoad> {
        val n = input.spansM.size
        if (req.boundaryPoints.size != n + 1) return emptyList()
        val boundaries = (0..n).filter {
            !(it == 0 && input.leftCondition == EndCondition.FREE) && !(it == n && input.rightCondition == EndCondition.FREE)
        }
        if (boundaries.size != a.reactions.size) return emptyList()
        return boundaries.mapIndexed { i, k ->
            val rigid = (k == 0 && input.leftCondition == EndCondition.FIXED) ||
                (k == n && input.rightCondition == EndCondition.FIXED)
            SupportLoad(
                point = req.boundaryPoints[k], beamName = name,
                deadKn = max(0.0, a.reactions[i].deadKn), liveKn = max(0.0, a.reactions[i].liveMaxKn),
                endMomentKnM = if (rigid) a.supportNegativeKnM[i] else 0.0
            )
        }
    }

    private fun buildInput(f: BeamForm): BeamInput? {
        fun fail(msg: String): BeamInput? {
            _state.update { it.copy(error = msg) }
            return null
        }

        val lengths = f.spans.map { it.length.num() }
        val dead = f.spans.map { it.dead.num() }
        val live = f.spans.map { it.live.num() }
        if (lengths.any { it == null } || dead.any { it == null } || live.any { it == null }) {
            return fail("Enter a valid number in every span field")
        }
        val points = ArrayList<BeamPointLoad>()
        for (p in f.points) {
            val span = p.span.trim().toIntOrNull()
            val pos = p.pos.num()
            val pd = p.dead.num()
            val pl = p.live.num()
            if (span == null || pos == null || pd == null || pl == null) return fail("Enter valid numbers for the point loads")
            points.add(BeamPointLoad(span - 1, pos, pd, pl))
        }
        val b = f.b.num(); val h = f.h.num(); val fc = f.fc.num(); val fy = f.fy.num(); val fyt = f.fyt.num()
        val cover = f.cover.num(); val st = f.stirrup.num(); val bar = f.bar.num(); val sup = f.support.num()
        val gd = f.gD.num(); val gl = f.gL.num()
        if (b == null || h == null || fc == null || fy == null || fyt == null || cover == null ||
            st == null || bar == null || sup == null || gd == null || gl == null
        ) return fail("Enter a valid number in every section / material field")

        val input = BeamInput(
            type = f.type,
            spansM = lengths.map { it!! },
            deadKnPerM = dead.map { it!! },
            liveKnPerM = live.map { it!! },
            pointLoads = points,
            bMm = b, hMm = h, fcMpa = fc, fyMpa = fy, fytMpa = fyt,
            coverMm = cover, stirrupDiaMm = st, barDiaMm = bar, supportWidthMm = sup,
            includeSelfWeight = f.selfWeight, deadFactor = gd, liveFactor = gl,
            leftEnd = f.leftEnd, rightEnd = f.rightEnd
        )
        input.validate()?.let { return fail(it) }
        return input
    }

    // ---- Report ------------------------------------------------------------------------------------------

    fun exportReport(uri: Uri) {
        val s = _state.value
        val a = s.analysis
        val d = s.design
        if (a == null || d == null) {
            _state.update { it.copy(message = "Run the study first") }
            return
        }
        val title = projectName
        val name = s.form.name
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { documents.write(uri) { out -> report.export(title, name, a, d, out) } }
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
