package com.inkwell.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Parsing + semantic validation for the `agent-output` contract, the client mirror
 * of the server's two-tier validation (schema tier, then semantic tier).
 *
 * Tier 1 (schema): [Json] deserialization into [AgentOutput]. A missing required
 * field or an unknown annotation `type` throws — the schema-tier rejection.
 *
 * Tier 2 (semantic): [validate] enforces the rules the JSON Schema cannot express,
 * exactly as `server/app/contracts/validation.py` does:
 *  - every numeric coordinate lies within the clamp tolerance band `[-0.05, 1.05]`
 *    (contract `coordinate-mapping`);
 *  - annotation `id`s are unique within a response;
 *  - every `anchor.annotation_id` references an annotation `id` in the same response.
 */
object AgentOutputContract {

    /** Lower/upper bound of the accepted coordinate band (contract coordinate-mapping). */
    const val BAND_MIN = -0.05
    const val BAND_MAX = 1.05

    val json: Json = Json {
        ignoreUnknownKeys = true // consumers ignore unknown optional props (contract §additive)
        explicitNulls = false
        classDiscriminator = "type"
    }

    /**
     * Deserialize an agent-output document (schema tier). Test harnesses and
     * production both strip the human-only `_reject_reason` key first, per
     * `contracts/fixtures/README.md`, so it never trips parsing.
     *
     * @throws kotlinx.serialization.SerializationException on a schema-tier failure.
     */
    fun parse(rawJson: String): AgentOutput {
        val stripped = stripRejectReason(json.parseToJsonElement(rawJson).jsonObject)
        return json.decodeFromJsonElement(AgentOutput.serializer(), stripped)
    }

    private fun stripRejectReason(obj: JsonObject): JsonObject =
        JsonObject(obj.filterKeys { it != "_reject_reason" })

    /**
     * Semantic validation tier. Returns an empty list when the document is valid,
     * otherwise a list of human-readable rule violations.
     */
    fun validate(output: AgentOutput): List<String> {
        val errors = mutableListOf<String>()

        // Coordinate tolerance band.
        output.annotations.forEachIndexed { i, a ->
            coordinatesOf(a).forEach { (path, value) ->
                if (value < BAND_MIN || value > BAND_MAX) {
                    errors += "annotations[$i].$path = $value outside [$BAND_MIN, $BAND_MAX]"
                }
            }
        }
        output.cards.forEachIndexed { ci, card ->
            card.anchors.forEachIndexed { ai, anchor ->
                anchor.region?.forEachIndexed { ri, v ->
                    if (v < BAND_MIN || v > BAND_MAX) {
                        errors += "cards[$ci].anchors[$ai].region[$ri] = $v outside band"
                    }
                }
            }
        }
        output.brainWrites.forEachIndexed { bi, bw ->
            bw.sourceRegion?.forEachIndexed { ri, v ->
                if (v < BAND_MIN || v > BAND_MAX) {
                    errors += "brain_writes[$bi].source_region[$ri] = $v outside band"
                }
            }
        }

        // Annotation id uniqueness.
        val seen = mutableSetOf<String>()
        output.annotations.forEach { a ->
            if (!seen.add(a.id)) errors += "duplicate annotation id '${a.id}'"
        }

        // Anchor referential integrity.
        val ids = output.annotations.map { it.id }.toSet()
        output.cards.forEachIndexed { ci, card ->
            card.anchors.forEachIndexed { ai, anchor ->
                val ref = anchor.annotationId
                if (ref != null && ref !in ids) {
                    errors += "cards[$ci].anchors[$ai].annotation_id '$ref' has no matching annotation"
                }
            }
        }

        return errors
    }

    /** True when [output] passes both tiers (semantic tier only; call after [parse]). */
    fun isValid(output: AgentOutput): Boolean = validate(output).isEmpty()

    /** Every numeric coordinate an annotation contributes, with a diagnostic path. */
    private fun coordinatesOf(a: Annotation): List<Pair<String, Double>> = buildList {
        fun points(name: String, pts: List<List<Double>>) {
            pts.forEachIndexed { j, p ->
                p.forEachIndexed { k, v -> add("$name[$j][$k]" to v) }
            }
        }
        when (a) {
            is Highlight -> points("points", a.points)
            is Underline -> points("points", a.points)
            is Strikethrough -> points("points", a.points)
            is Path -> points("points", a.points)
            is Arrow -> {
                a.from.forEachIndexed { k, v -> add("from[$k]" to v) }
                a.to.forEachIndexed { k, v -> add("to[$k]" to v) }
            }
            is Ellipse -> {
                a.center.forEachIndexed { k, v -> add("center[$k]" to v) }
                add("rx" to a.rx)
                add("ry" to a.ry)
            }
            is RectAnnotation -> {
                add("x" to a.x); add("y" to a.y); add("w" to a.w); add("h" to a.h)
            }
            is Text -> a.at.forEachIndexed { k, v -> add("at[$k]" to v) }
            is MarginNote -> add("y" to a.y)
        }
    }
}
