package com.sharfo.myplans.domain.usecase

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.sharfo.myplans.domain.model.ProjectPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.min

class ExportPdfUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    operator fun invoke(project: ProjectPlan): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = project.architectural.projectName.replace(" ", "_")
        val file = File(exportDir, "${safeName}_${project.id}.pdf")

        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas: Canvas = page.canvas

        val titlePaint = Paint().apply {
            color = Color.rgb(13, 71, 161)
            textSize = 16f
            isFakeBoldText = true
            textAlign = Paint.Align.RIGHT
        }
        val bodyPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 11f
            textAlign = Paint.Align.RIGHT
        }
        val wallPaint = Paint().apply {
            color = Color.rgb(25, 118, 210)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val colPaint = Paint().apply {
            color = Color.rgb(211, 47, 47)
            style = Paint.Style.FILL
        }
        val roomTextPaint = Paint().apply {
            color = Color.BLACK
            textSize = 9f
            textAlign = Paint.Align.CENTER
        }

        // Header
        canvas.drawText("تطبيق مخططاتي - المخطط المعماري والإنشائي", 555f, 40f, titlePaint)
        canvas.drawText(
            "المشروع: ${project.architectural.projectName} | المساحة: ${project.architectural.landAreaSqm} م2 | عدد الطوابق: ${project.architectural.floorsCount}",
            555f,
            62f,
            bodyPaint
        )
        canvas.drawText(
            "الكود المعتمد: ${project.structural.buildingCode.titleAr} | نوع البلاطة: ${project.structural.slabType.titleAr}",
            555f,
            80f,
            bodyPaint
        )

        // Drawing area box
        val drawLeft = 60f
        val drawTop = 110f
        val maxDrawW = 475f
        val maxDrawH = 380f

        val scaleX = maxDrawW / project.generated.plotWidthM.toFloat()
        val scaleY = maxDrawH / project.generated.plotDepthM.toFloat()
        val scale = min(scaleX, scaleY)

        project.generated.rooms.forEach { room ->
            val rx = drawLeft + (room.x.toFloat() * scale)
            val ry = drawTop + (room.y.toFloat() * scale)
            val rw = room.width.toFloat() * scale
            val rh = room.height.toFloat() * scale
            canvas.drawRect(rx, ry, rx + rw, ry + rh, wallPaint)
            canvas.drawText(room.name, rx + rw / 2f, ry + rh / 2f - 4f, roomTextPaint)
            canvas.drawText("${room.width}×${room.height} م", rx + rw / 2f, ry + rh / 2f + 10f, roomTextPaint)
        }

        project.generated.columns.forEach { col ->
            val cx = drawLeft + (col.x.toFloat() * scale)
            val cy = drawTop + (col.y.toFloat() * scale)
            canvas.drawRect(cx - 4f, cy - 4f, cx + 4f, cy + 4f, colPaint)
        }

        // Structural Schedule Summary
        var textY = 530f
        canvas.drawText("الجدول الإنشائي والتسليح وحصر الكميات:", 555f, textY, titlePaint)
        textY += 24f

        val sched = project.generated.schedule
        val sampleCol = project.generated.columns.firstOrNull()
        val lines = listOf(
            "• سماكة البلاطة الإنشائية: ${sched.slabThicknessCm} سم",
            "• تسليح البلاطة الرئيسي: ${sched.slabMainReinforcement} | الثانوي: ${sched.slabSecondaryReinforcement}",
            "• أبعاد الجسور (الكمرات): ${sched.beamDimensionsCm}",
            "• تسليح الجسور: ${sched.beamBottomReinforcement} | ${sched.beamTopReinforcement}",
            "• نموذج الأعمدة (${project.generated.columns.size} عمود): ${sampleCol?.widthCm ?: 25}×${sampleCol?.heightCm ?: 40} سم - التسليح: ${sampleCol?.mainBarsCount ?: 8} Ø${sampleCol?.mainBarDiaMm ?: 16}",
            "• إجمالي كمية البيتون المسلح التقديرية للمبنى: ${sched.totalConcreteVolumeM3} متر مكعب",
            "• إجمالي وزن حديد التسليح التقديري للمبنى: ${sched.estimatedSteelWeightTon} طن"
        )

        lines.forEach { line ->
            canvas.drawText(line, 555f, textY, bodyPaint)
            textY += 20f
        }

        pdfDocument.finishPage(page)
        FileOutputStream(file).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()
        return file
    }
}
