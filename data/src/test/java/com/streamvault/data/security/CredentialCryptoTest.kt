package com.streamvault.data.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CredentialCryptoTest {

    @Test
    fun `decryptIfNeeded throws credential exception for unreadable encrypted value`() {
        val failure = runCatching {
            CredentialCrypto.decryptIfNeeded("enc:v1:not-valid-base64")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CredentialDecryptionException::class.java)
        assertThat(failure).hasMessageThat().contains("Please re-enter your provider credentials")
    }
}