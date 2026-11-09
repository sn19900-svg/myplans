package com.myprojects.cad.domain.usecase

import com.myprojects.cad.core.geometry.Vec2
import com.myprojects.cad.domain.model.Wall
import javax.inject.Inject

/** Builds a wall from explicit numeric input: start point, length (m), angle (deg CCW from east), thickness (m). */
class CreateWallFromPolarUseCase @Inject constructor() {
    operator fun invoke(start: Vec2, lengthM: Double, angleDeg: Double, thicknessM: Double): Result<Wall> {
        if (!lengthM.isFinite() || lengthM < MIN_LENGTH_M) {
            return Result.failure(IllegalArgumentException("Length must be at least 1 mm"))
        }
        if (!angleDeg.isFinite()) {
            return Result.failure(IllegalArgumentException("Invalid angle"))
        }
        if (thicknessM !in MIN_THICKNESS_M..MAX_THICKNESS_M) {
            return Result.failure(IllegalArgumentException("Thickness must be 5 cm to 100 cm"))
        }
        val end = start + Vec2.fromPolar(lengthM, Math.toRadians(angleDeg))
        return Result.success(Wall(start = start, end = end, thicknessM = thicknessM))
    }

    companion object {
        const val MIN_LENGTH_M = 0.001
        const val MIN_THICKNESS_M = 0.05
        const val MAX_THICKNESS_M = 1.0
    }
}
