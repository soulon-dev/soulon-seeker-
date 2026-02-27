package com.soulon.app.persona

import com.soulon.app.rewards.EvidenceDirection
import com.soulon.app.rewards.EvidenceSourceType
import com.soulon.app.rewards.EvidenceSourceV2
import com.soulon.app.rewards.OceanProfileV2
import com.soulon.app.rewards.PersonaData
import com.soulon.app.rewards.PersonaEvidenceV2
import com.soulon.app.rewards.PersonaProfileV2
import com.soulon.app.rewards.PersonaTrait
import com.soulon.app.rewards.TraitDistributionV2

object PersonaProfileUpdateEngine {
    fun updateFromPointEstimate(
        existing: PersonaProfileV2?,
        estimate: PersonaData,
        timestamp: Long,
        sourceType: EvidenceSourceType,
        sourceId: String? = null
    ): PersonaProfileV2 {
        val weight = estimateWeight(estimate.sampleSize)
        val base = existing ?: PersonaProfileV2(
            ocean = OceanProfileV2(
                openness = TraitDistributionV2.prior(timestamp),
                conscientiousness = TraitDistributionV2.prior(timestamp),
                extraversion = TraitDistributionV2.prior(timestamp),
                agreeableness = TraitDistributionV2.prior(timestamp),
                neuroticism = TraitDistributionV2.prior(timestamp)
            ),
            updatedAt = timestamp,
            sampleCount = 0,
            evidence = emptyList()
        )

        val updatedOcean = OceanProfileV2(
            openness = base.ocean.openness.updateFromPoint(estimate.openness, weight, timestamp),
            conscientiousness = base.ocean.conscientiousness.updateFromPoint(estimate.conscientiousness, weight, timestamp),
            extraversion = base.ocean.extraversion.updateFromPoint(estimate.extraversion, weight, timestamp),
            agreeableness = base.ocean.agreeableness.updateFromPoint(estimate.agreeableness, weight, timestamp),
            neuroticism = base.ocean.neuroticism.updateFromPoint(estimate.neuroticism, weight, timestamp)
        )

        val evidence = buildEvidence(
            estimate = estimate,
            timestamp = timestamp,
            weight = weight,
            sourceType = sourceType,
            sourceId = sourceId
        )

        val mergedEvidence = (base.evidence + evidence)
            .sortedByDescending { it.timestamp }
            .take(30)

        return base.copy(
            ocean = updatedOcean,
            updatedAt = timestamp,
            sampleCount = (base.sampleCount + estimate.sampleSize).coerceAtLeast(estimate.sampleSize),
            evidence = mergedEvidence
        )
    }

    private fun estimateWeight(sampleSize: Int): Float {
        return when {
            sampleSize >= 20 -> 8f
            sampleSize >= 10 -> 5f
            sampleSize >= 5 -> 3f
            else -> 1.5f
        }
    }

    private fun buildEvidence(
        estimate: PersonaData,
        timestamp: Long,
        weight: Float,
        sourceType: EvidenceSourceType,
        sourceId: String?
    ): List<PersonaEvidenceV2> {
        val source = EvidenceSourceV2(type = sourceType, id = sourceId, createdAt = timestamp)
        return listOf(
            evidenceForTrait(PersonaTrait.OPENNESS, estimate.openness, weight, timestamp, source),
            evidenceForTrait(PersonaTrait.CONSCIENTIOUSNESS, estimate.conscientiousness, weight, timestamp, source),
            evidenceForTrait(PersonaTrait.EXTRAVERSION, estimate.extraversion, weight, timestamp, source),
            evidenceForTrait(PersonaTrait.AGREEABLENESS, estimate.agreeableness, weight, timestamp, source),
            evidenceForTrait(PersonaTrait.NEUROTICISM, estimate.neuroticism, weight, timestamp, source),
        )
    }

    private fun evidenceForTrait(
        trait: PersonaTrait,
        score: Float,
        weight: Float,
        timestamp: Long,
        source: EvidenceSourceV2
    ): PersonaEvidenceV2 {
        val direction = when {
            score >= 0.6f -> EvidenceDirection.INCREASE
            score <= 0.4f -> EvidenceDirection.DECREASE
            else -> EvidenceDirection.NEUTRAL
        }
        val summary = when (direction) {
            EvidenceDirection.INCREASE -> "该维度表现偏高（当前估计 ${formatScore(score)}）"
            EvidenceDirection.DECREASE -> "该维度表现偏低（当前估计 ${formatScore(score)}）"
            EvidenceDirection.NEUTRAL -> "该维度表现中性（当前估计 ${formatScore(score)}）"
        }
        return PersonaEvidenceV2(
            trait = trait,
            direction = direction,
            weight = weight,
            timestamp = timestamp,
            source = source,
            summary = summary
        )
    }

    private fun formatScore(v: Float): String {
        val pct = (v.coerceIn(0f, 1f) * 100).toInt()
        return "${pct}%"
    }
}

