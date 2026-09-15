package com.inkwell.ui

import com.inkwell.contracts.Annotation
import com.inkwell.contracts.AgentOutputContract

/**
 * Debug-only sample agent output for the "Render fixture" action. The JSON is the
 * frozen `contracts/fixtures/agent-output/valid-full-vocabulary.json`, embedded so the
 * debug tool works on-device before a server exists (the contracts/ tree is not bundled
 * in the APK). Parsed through the real [AgentOutputContract] so it exercises the same
 * path as production. Referenced only from `BuildConfig.DEBUG`-gated code.
 */
object DebugFixtures {

    /** Verbatim copy of contracts/fixtures/agent-output/valid-full-vocabulary.json. */
    const val VALID_FULL_VOCABULARY_JSON: String = """
{
  "summary": "Marked up the architecture sketch: one blocking dependency and a units mismatch.",
  "annotations": [
    { "id": "a1", "type": "highlight", "points": [[0.10,0.20],[0.40,0.20],[0.40,0.30],[0.10,0.30]] },
    { "id": "a2", "type": "arrow", "from": [0.20,0.40], "to": [0.70,0.50], "label": "blocks", "color": "#D9480F" },
    { "id": "a3", "type": "ellipse", "center": [0.50,0.50], "rx": 0.12, "ry": 0.08 },
    { "id": "a4", "type": "rect", "x": 0.10, "y": 0.10, "w": 0.30, "h": 0.20 },
    { "id": "a5", "type": "underline", "points": [[0.10,0.50],[0.60,0.50]] },
    { "id": "a6", "type": "strikethrough", "points": [[0.10,0.55],[0.60,0.55]] },
    { "id": "a7", "type": "path", "points": [[0.10,0.10],[0.20,0.30],[0.40,0.20]], "closed": false },
    { "id": "a8", "type": "text", "at": [0.70,0.20], "text": "check units", "size": 0.02 },
    { "id": "a9", "type": "margin_note", "y": 0.35, "text": "This contradicts p.2" }
  ],
  "cards": [
    {
      "kind": "task", "title": "Resolve the blocking dependency",
      "body": "The **auth service** must ship before the gateway can route.",
      "anchors": [ { "annotation_id": "a2" }, { "region": [0.20, 0.40, 0.50, 0.10] } ],
      "actions": [ { "id": "c1", "label": "Save as task", "kind": "save_to_brain", "payload": { "kind": "task" } } ]
    }
  ],
  "brain_writes": [
    { "kind": "decision", "text": "Gateway depends on auth; ship auth first.", "tags": ["architecture"], "source_region": [0.20, 0.40, 0.50, 0.10] }
  ]
}
"""

    /** The fixture's annotations (AnnotationRenderer draws the highlight; rest ignored this stage). */
    val annotations: List<Annotation> by lazy {
        AgentOutputContract.parse(VALID_FULL_VOCABULARY_JSON).annotations
    }
}
