package com.myprojects.cad.presentation.beam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myprojects.cad.domain.structural.BeamAnalysis
import com.myprojects.cad.domain.structural.BeamDesign
import com.myprojects.cad.domain.structural.BeamDesigner
import com.myprojects.cad.domain.structural.BeamInput
import com.myprojects.cad.domain.structural.BeamPointLoad
import com.myprojects.cad.domain.structural.BeamSolver
import com.myprojects.cad.domain.structural.BeamType
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

data class SpanForm(val length: String = "5", val dead: String = "10", val live: String = "5")

data class PointForm(val span: String = "1", val pos: String = "2.5", val dead: String = "0", val live: String = "0")

data class BeamForm(
    val type: BeamType = BeamType.SIMPLY_SUPPORTED,
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
    val busy: Boolean = false
)

private fun String.num(): Double? = replace(',', '.').trim().toDoubleOrNull()

@HiltViewModel
class BeamStudyViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(BeamUiState())
    val state: StateFlow<BeamUiState> = _state.asStateFlow()

    /** Any edit invalidates the previous results so stale numbers are never shown. */
    fun update(transform: (BeamForm) -> BeamForm) {
        _state.update { it.copy(form = transform(it.form), analysis = null, design = null, error = null) }
    }

    fun setType(type: BeamType) = update { f ->
        val spans = when (type) {
            BeamType.CONTINUOUS -> if (f.spans.size >= 2) f.spans else f.spans + f.spans.last()
            else -> listOf(f.spans.first())
        }
        f.copy(type = type, spans = spans, points = f.points.filter { (it.span.toIntOrNull() ?: 1) <= spans.size })
    }

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

    /** Spans measured from lines/walls selected in the drawing. */
    fun prefill(lengths: List<Double>) {
        if (lengths.isEmpty()) return
        update { f ->
            val take = lengths.take(8)
            val type = when {
                take.size >= 2 -> BeamType.CONTINUOUS
                f.type == BeamType.CANTILEVER -> BeamType.CANTILEVER
                else -> BeamType.SIMPLY_SUPPORTED
            }
            val template = f.spans.last()
            val spans = take.mapIndexed { i, l -> (f.spans.getOrNull(i) ?: template).copy(length = fmt(l)) }
            f.copy(type = type, spans = spans, points = emptyList())
        }
    }

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
            _state.update { s ->
                result.fold(
                    { (a, d) -> s.copy(busy = false, analysis = a, design = d) },
                    { e -> s.copy(busy = false, error = e.message ?: "Calculation failed") }
                )
            }
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
            includeSelfWeight = f.selfWeight, deadFactor = gd, liveFactor = gl
        )
        input.validate()?.let { return fail(it) }
        return input
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
