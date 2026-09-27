package com.jothivel.chits.data.firebase

import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity check that this build actually has the one fixed cloud account compiled in - see cloud.properties.example. */
class AdminAccountTest {
    @Test fun `the build has one fixed cloud account`() {
        assertTrue("cloud.properties is missing - copy cloud.properties.example and fill it in", AdminAccount.hasCredentials)
        assertTrue(AdminAccount.EMAIL.contains("@"))
        assertTrue(AdminAccount.PASSWORD.isNotBlank())
    }
}
