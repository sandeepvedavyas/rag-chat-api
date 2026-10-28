package com.sandeepvedavyas.rag.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ApiKeyVerifierTest {
    private val verifier = ApiKeyVerifier("local-dev-key")

    @Test
    fun `accepts a bearer token with any scheme casing`() {
        assertThat(verifier.matches("Bearer local-dev-key")).isTrue()
        assertThat(verifier.matches("bearer local-dev-key")).isTrue()
        assertThat(verifier.matches("  Bearer   local-dev-key  ")).isTrue()
    }

    @Test
    fun `rejects missing, wrong, and non-bearer credentials`() {
        assertThat(verifier.matches(null)).isFalse()
        assertThat(verifier.matches("")).isFalse()
        assertThat(verifier.matches("Bearer")).isFalse()
        assertThat(verifier.matches("Bearer ")).isFalse()
        assertThat(verifier.matches("Basic local-dev-key")).isFalse()
        assertThat(verifier.matches("Bearer wrong-key")).isFalse()
        assertThat(verifier.matches("Bearer local-dev-key extra")).isFalse()
    }

    @Test
    fun `a blank configured key fails closed`() {
        assertThat(ApiKeyVerifier("").matches("Bearer ")).isFalse()
        assertThat(ApiKeyVerifier("   ").matches("Bearer    ")).isFalse()
    }
}
