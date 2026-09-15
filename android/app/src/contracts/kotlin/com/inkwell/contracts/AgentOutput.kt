package com.inkwell.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin data model for the frozen `agent-output` contract v1.
 *
 * Source of truth: `contracts/schema/agent-output.v1.schema.json` (ADR-0007).
 *
 * ADR-0007 prefers generating these types with quicktype, but quicktype's
 * kotlinx-serialization target cannot express the closed `anyOf` annotation union
 * as a discriminated sealed hierarchy — it flattens every variant into one class
 * with all fields optional and loses the per-type shape. This file is therefore the
 * ADR-0007-sanctioned committed fallback, kept honest by the shared fixture tests
 * (`AgentOutputContractTest`) which must accept every `valid-*` fixture and reject
 * every `invalid-*` fixture in `contracts/fixtures/agent-output/`.
 *
 * All geometry is normalized `[0,1]` relative to the exported image bounds
 * (contract `coordinate-mapping`). Numeric-range/semantic rules are enforced by
 * [validate], mirroring the server.
 */
@Serializable
data class AgentOutput(
    val summary: String,
    val annotations: List<Annotation> = emptyList(),
    val cards: List<Card> = emptyList(),
    @SerialName("brain_writes") val brainWrites: List<BrainWrite> = emptyList(),
)

/**
 * Closed annotation vocabulary (v1). The wire discriminator is the `type` field;
 * kotlinx uses it as the class discriminator, so subclasses must not redeclare it.
 * An unknown `type` fails deserialization (rejected at the schema tier).
 */
@Serializable
sealed class Annotation {
    abstract val id: String
    abstract val color: String?
    abstract val label: String?
}

@Serializable
@SerialName("highlight")
data class Highlight(
    override val id: String,
    val points: List<List<Double>>,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("arrow")
data class Arrow(
    override val id: String,
    val from: List<Double>,
    val to: List<Double>,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("ellipse")
data class Ellipse(
    override val id: String,
    val center: List<Double>,
    val rx: Double,
    val ry: Double,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("rect")
data class RectAnnotation(
    override val id: String,
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("underline")
data class Underline(
    override val id: String,
    val points: List<List<Double>>,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("strikethrough")
data class Strikethrough(
    override val id: String,
    val points: List<List<Double>>,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("path")
data class Path(
    override val id: String,
    val points: List<List<Double>>,
    val closed: Boolean,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("text")
data class Text(
    override val id: String,
    val at: List<Double>,
    val text: String,
    val size: Double,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
@SerialName("margin_note")
data class MarginNote(
    override val id: String,
    val y: Double,
    val text: String,
    override val color: String? = null,
    override val label: String? = null,
) : Annotation()

@Serializable
data class Anchor(
    @SerialName("annotation_id") val annotationId: String? = null,
    val region: List<Double>? = null,
)

@Serializable
data class CardAction(
    val id: String,
    val label: String,
    val kind: CardActionKind,
    val payload: kotlinx.serialization.json.JsonObject,
)

@Serializable
enum class CardActionKind {
    @SerialName("confirm") CONFIRM,
    @SerialName("reject") REJECT,
    @SerialName("run_tool") RUN_TOOL,
    @SerialName("open_canvas") OPEN_CANVAS,
    @SerialName("save_to_brain") SAVE_TO_BRAIN,
}

@Serializable
enum class CardKind {
    @SerialName("answer") ANSWER,
    @SerialName("task") TASK,
    @SerialName("fact") FACT,
    @SerialName("question") QUESTION,
    @SerialName("action") ACTION,
    @SerialName("error") ERROR,
}

@Serializable
data class Card(
    val kind: CardKind,
    val title: String,
    val body: String,
    val anchors: List<Anchor> = emptyList(),
    val actions: List<CardAction> = emptyList(),
)

@Serializable
enum class BrainWriteKind {
    @SerialName("fact") FACT,
    @SerialName("task") TASK,
    @SerialName("reference") REFERENCE,
    @SerialName("decision") DECISION,
}

@Serializable
data class BrainWrite(
    val kind: BrainWriteKind,
    val text: String,
    val tags: List<String> = emptyList(),
    @SerialName("source_region") val sourceRegion: List<Double>? = null,
)
