package com.sharfo.myplans.domain.model

enum class BuildingCode(val titleAr: String, val codeSymbol: String, val deadLoadFactor: Double, val liveLoadFactor: Double) {
    SYRIAN("الكود العربي السوري", "SAC", 1.4, 1.7),
    EGYPTIAN("الكود المصري ECP 203", "ECP-203", 1.4, 1.6),
    ACI("الكود الأمريكي ACI 318", "ACI-318", 1.2, 1.6),
    SAUDI("الكود السعودي SBC 304", "SBC-304", 1.2, 1.6)
}

enum class SlabType(val titleAr: String, val codeName: String) {
    SOLID("بلاطة مصمتة (Solid Slab)", "SOLID"),
    RIBBED("بلاطة هوردي (Ribbed Slab)", "RIBBED"),
    FLAT("بلاطة فلات (Flat Slab)", "FLAT")
}

data class StructuralInput(
    val buildingCode: BuildingCode = BuildingCode.SYRIAN,
    val slabType: SlabType = SlabType.SOLID,
    val preferredSlabBarDiaMm: Int = 12,
    val preferredBeamBarDiaMm: Int = 16,
    val preferredColumnBarDiaMm: Int = 18,
    val concreteFckMpa: Int = 25,
    val steelFyMpa: Int = 400
)
