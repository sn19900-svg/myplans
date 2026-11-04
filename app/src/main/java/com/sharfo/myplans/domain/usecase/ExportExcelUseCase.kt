package com.sharfo.myplans.domain.usecase

import android.content.Context
import com.sharfo.myplans.domain.model.ProjectPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class ExportExcelUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    operator fun invoke(project: ProjectPlan): File {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = project.architectural.projectName.replace(" ", "_")
        val file = File(exportDir, "${safeName}_${project.id}.xls")

        val sched = project.generated.schedule
        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<?mso-application progid="Excel.Sheet"?>""")
            appendLine("""<Workbook xmlns="urn:schemas-microsoft-com:office:spreadsheet" xmlns:ss="urn:schemas-microsoft-com:office:spreadsheet">""")
            
            // Sheet 1: Structural & BOQ
            appendLine("""<Worksheet ss:Name="التسليح والكميات"><Table>""")
            appendRow("البند", "القيمة / التوصيف")
            appendRow("اسم المشروع", project.architectural.projectName)
            appendRow("مساحة الأرض (م2)", project.architectural.landAreaSqm.toString())
            appendRow("عدد الطوابق", project.architectural.floorsCount.toString())
            appendRow("الكود الهندسي", project.structural.buildingCode.titleAr)
            appendRow("نوع البلاطة", project.structural.slabType.titleAr)
            appendRow("سماكة البلاطة (سم)", sched.slabThicknessCm.toString())
            appendRow("تسليح البلاطة الرئيسي", sched.slabMainReinforcement)
            appendRow("تسليح البلاطة الثانوي", sched.slabSecondaryReinforcement)
            appendRow("أبعاد الجسور", sched.beamDimensionsCm)
            appendRow("تسليح الجسور السفلي", sched.beamBottomReinforcement)
            appendRow("تسليح الجسور العلوي", sched.beamTopReinforcement)
            appendRow("حجم البيتون الكلي (م3)", sched.totalConcreteVolumeM3.toString())
            appendRow("وزن الحديد الكلي (طن)", sched.estimatedSteelWeightTon.toString())
            appendLine("""</Table></Worksheet>""")

            // Sheet 2: Rooms Schedule
            appendLine("""<Worksheet ss:Name="جدول الغرف"><Table>""")
            appendRow("اسم الفراغ", "العرض (م)", "الطول (م)", "المساحة (م2)")
            project.generated.rooms.forEach { r ->
                appendRow(r.name, r.width.toString(), r.height.toString(), String.format("%.2f", r.area))
            }
            appendLine("""</Table></Worksheet>""")

            // Sheet 3: Columns Schedule
            appendLine("""<Worksheet ss:Name="جدول الأعمدة"><Table>""")
            appendRow("رمز العمود", "X (م)", "Y (م)", "الأبعاد (سم)", "التسليح الطولي", "الأساور")
            project.generated.columns.forEach { c ->
                appendRow(
                    c.id,
                    c.x.toString(),
                    c.y.toString(),
                    "${c.widthCm}x${c.heightCm}",
                    "${c.mainBarsCount} Ø${c.mainBarDiaMm}",
                    c.stirrupText
                )
            }
            appendLine("""</Table></Worksheet>""")

            appendLine("""</Workbook>""")
        }

        file.writeText(xml, Charsets.UTF_8)
        return file
    }

    private fun StringBuilder.appendRow(vararg cells: String) {
        appendLine("<Row>")
        cells.forEach { cell ->
            val escaped = cell
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            appendLine("""<Cell><Data ss:Type="String">$escaped</Data></Cell>""")
        }
        appendLine("</Row>")
    }
}
