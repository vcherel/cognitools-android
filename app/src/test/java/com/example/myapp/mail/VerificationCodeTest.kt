package com.example.myapp.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationCodeTest {

    @Test
    fun `a code in the subject is found`() {
        assertEquals("482913", findVerificationCode("482913 is your Instagram code", ""))
    }

    @Test
    fun `a code in a french body is found`() {
        assertEquals("739201", findVerificationCode("Connexion", "Bonjour, votre code de vérification est : 739201. Il expire dans 10 minutes."))
    }

    @Test
    fun `a code split in two groups comes back whole`() {
        assertEquals("123456", findVerificationCode("", "Your security code: 123 456"))
    }

    @Test
    fun `a short code is found`() {
        assertEquals("4829", findVerificationCode("", "Use this code to sign in: 4829"))
    }

    @Test
    fun `a mixed letters and digits code is found`() {
        assertEquals("K7X9PQ", findVerificationCode("", "Your login code is K7X9PQ"))
    }

    @Test
    fun `the digits after a service prefix are the code`() {
        assertEquals("583014", findVerificationCode("G-583014 is your Google verification code", ""))
    }

    @Test
    fun `the copyright year is not the code`() {
        assertEquals("739201", findVerificationCode("", "Your verification code is 739201. © 2025 Company"))
    }

    @Test
    fun `a number with no code word nearby is ignored`() {
        assertNull(findVerificationCode("Commande 123456 expédiée", "Votre commande 123456 est en route."))
    }

    @Test
    fun `prices and times are not codes`() {
        assertNull(findVerificationCode("Code promo", "Profitez de -20% sur 1500 € jusqu'à 18:30"))
    }
}
