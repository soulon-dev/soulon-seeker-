package com.soulon.app.rewards

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class PersonaProfileV2(
    val version: Int = 2,
    val ocean: OceanProfileV2,
    val updatedAt: Long,
    val sampleCount: Int,
    val evidence: List<PersonaEvidenceV2> = emptyList()
) {
    fun toLegacyPersonaData(): PersonaData {
        return PersonaData(
            openness = ocean.openness.mean,
            conscientiousness = ocean.conscientiousness.mean,
            extraversion = ocean.extraversion.mean,
            agreeableness = ocean.agreeableness.mean,
            neuroticism = ocean.neuroticism.mean,
            analyzedAt = updatedAt,
            sampleSize = sampleCount
        )
    }
}

data class OceanProfileV2(
    val openness: TraitDistributionV2,
    val conscientiousness: TraitDistributionV2,
    val extraversion: TraitDistributionV2,
    val agreeableness: TraitDistributionV2,
    val neuroticism: TraitDistributionV2
) {
    companion object {
        fun fromPointEstimate(ocean: PersonaData, weight: Float, updatedAt: Long): OceanProfileV2 {
            return OceanProfileV2(
                openness = TraitDistributionV2.fromPoint(ocean.openness, weight, updatedAt),
                conscientiousness = TraitDistributionV2.fromPoint(ocean.conscientiousness, weight, updatedAt),
                extraversion = TraitDistributionV2.fromPoint(ocean.extraversion, weight, updatedAt),
                agreeableness = TraitDistributionV2.fromPoint(ocean.agreeableness, weight, updatedAt),
                neuroticism = TraitDistributionV2.fromPoint(ocean.neuroticism, weight, updatedAt)
            )
        }
    }
}

data class TraitDistributionV2(
    val alpha: Float,
    val beta: Float,
    val updatedAt: Long
) {
    val mean: Float get() = (alpha / (alpha + beta)).coerceIn(0f, 1f)

    val confidence: Float
        get() {
            val strength = (alpha + beta).coerceAtLeast(2f)
            val normalized = (kotlin.math.ln(1f + strength) / kotlin.math.ln(1f + 40f)).toFloat()
            return normalized.coerceIn(0f, 1f)
        }

    fun updateFromPoint(score: Float, weight: Float, timestamp: Long): TraitDistributionV2 {
        val s = score.coerceIn(0f, 1f)
        val w = weight.coerceAtLeast(0.1f)
        return TraitDistributionV2(
            alpha = (alpha + s * w).coerceAtLeast(0.0001f),
            beta = (beta + (1f - s) * w).coerceAtLeast(0.0001f),
            updatedAt = timestamp
        )
    }

    companion object {
        fun prior(timestamp: Long): TraitDistributionV2 = TraitDistributionV2(alpha = 1f, beta = 1f, updatedAt = timestamp)

        fun fromPoint(score: Float, weight: Float, timestamp: Long): TraitDistributionV2 {
            return prior(timestamp).updateFromPoint(score, weight, timestamp)
        }
    }
}

data class PersonaEvidenceV2(
    val trait: PersonaTrait,
    val direction: EvidenceDirection,
    val weight: Float,
    val timestamp: Long,
    val source: EvidenceSourceV2,
    val summary: String
)

data class EvidenceSourceV2(
    val type: EvidenceSourceType,
    val id: String?,
    val createdAt: Long?
)

enum class EvidenceSourceType {
    @SerializedName("onboarding")
    ONBOARDING,
    @SerializedName("chat")
    CHAT,
    @SerializedName("memory")
    MEMORY,
    @SerializedName("unknown")
    UNKNOWN
}

enum class PersonaTrait {
    OPENNESS,
    CONSCIENTIOUSNESS,
    EXTRAVERSION,
    AGREEABLENESS,
    NEUROTICISM
}

enum class EvidenceDirection {
    INCREASE,
    DECREASE,
    NEUTRAL
}

object PersonaProfileV2Json {
    private val gson = Gson()

    fun toJson(profile: PersonaProfileV2): String = gson.toJson(profile)

    fun fromJson(json: String): PersonaProfileV2? = runCatching { gson.fromJson(json, PersonaProfileV2::class.java) }.getOrNull()
}

