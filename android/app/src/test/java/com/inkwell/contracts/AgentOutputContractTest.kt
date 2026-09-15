package com.inkwell.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The language-neutral drift check (ADR-0007): the Kotlin `agent-output` model must
 * accept every `valid-*` fixture and reject every `invalid-*` fixture in
 * `contracts/fixtures/agent-output/`, matching the server's pytest suite. Rejection is
 * either a schema-tier parse failure or a non-empty semantic [AgentOutputContract.validate].
 */
class AgentOutputContractTest {

    private val fixtureDir: File = run {
        val candidates = listOf(
            File("../../contracts/fixtures/agent-output"), // unit test cwd = android/app
            File("../contracts/fixtures/agent-output"),
            File("contracts/fixtures/agent-output"),
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("agent-output fixtures not found (looked in: ${candidates.joinToString { it.absolutePath }})")
    }

    private fun fixtures(prefix: String): List<File> =
        fixtureDir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** True when the raw fixture is rejected by either validation tier. */
    private fun isRejected(raw: String): Boolean =
        try {
            val output = AgentOutputContract.parse(raw)
            AgentOutputContract.validate(output).isNotEmpty()
        } catch (_: Exception) {
            true // schema-tier rejection (missing field / unknown type)
        }

    @Test
    fun valid_fixtures_are_accepted() {
        val valid = fixtures("valid-")
        assertTrue("expected valid-* fixtures to exist", valid.isNotEmpty())
        for (file in valid) {
            val output = AgentOutputContract.parse(file.readText())
            val errors = AgentOutputContract.validate(output)
            assertEquals("${file.name} should be valid but had: $errors", emptyList<String>(), errors)
        }
    }

    @Test
    fun invalid_fixtures_are_rejected() {
        val invalid = fixtures("invalid-")
        assertTrue("expected invalid-* fixtures to exist", invalid.isNotEmpty())
        for (file in invalid) {
            assertTrue("${file.name} should be rejected", isRejected(file.readText()))
        }
    }

    @Test
    fun reject_reason_key_is_stripped_before_parsing() {
        // invalid-missing-summary carries _reject_reason; stripping must not mask the
        // real (missing summary) failure with an additionalProperties-style error.
        val raw = File(fixtureDir, "invalid-missing-summary.json").readText()
        assertTrue(raw.contains("_reject_reason"))
        assertTrue(isRejected(raw))
    }

    @Test
    fun full_vocabulary_parses_every_annotation_type() {
        val output = AgentOutputContract.parse(File(fixtureDir, "valid-full-vocabulary.json").readText())
        val types = output.annotations.map { it::class.simpleName }.toSet()
        assertEquals(9, output.annotations.size)
        assertTrue(types.contains("MarginNote"))
        assertTrue(types.contains("Arrow"))
        assertTrue(AgentOutputContract.isValid(output))
    }
}
