package com.inkwell.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Error-envelope parser: `{ "error": { "code", "message" } }` (contract device-api). */
class ErrorEnvelopeTest {

    @Test
    fun parses_the_error_envelope() {
        val raw = """{"error":{"code":"invalid_token","message":"unknown or revoked token"}}"""
        val body = ErrorEnvelopeParser.parse(raw)
        assertEquals("invalid_token", body?.code)
        assertEquals("unknown or revoked token", body?.message)
    }

    @Test
    fun tolerates_extra_keys() {
        val raw = """{"error":{"code":"rate_limited","message":"slow down","retry_after":5}}"""
        assertEquals("rate_limited", ErrorEnvelopeParser.parse(raw)?.code)
    }

    @Test
    fun returns_null_on_non_envelope_or_blank() {
        assertNull(ErrorEnvelopeParser.parse(null))
        assertNull(ErrorEnvelopeParser.parse(""))
        assertNull(ErrorEnvelopeParser.parse("""{"detail":"not an envelope"}"""))
        assertNull(ErrorEnvelopeParser.parse("not json at all"))
    }
}
