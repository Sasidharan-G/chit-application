package com.jothivel.chits.data.firebase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentCredentialStoreTest {
    private lateinit var app: Application

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        AgentCredentialStore.clear(app)
    }

    @After fun tearDown() = AgentCredentialStore.clear(app)

    @Test fun `nothing is known about an agent this phone did not create`() {
        assertNull(AgentCredentialStore.get(app, "uid-1"))
    }

    @Test fun `the generation and PIN of each agent are kept apart`() {
        AgentCredentialStore.put(app, "uid-1", 0, "4826")
        AgentCredentialStore.put(app, "uid-2", 2, "1357")
        assertEquals(AgentCredentialStore.Creds(0, "4826"), AgentCredentialStore.get(app, "uid-1"))
        assertEquals(AgentCredentialStore.Creds(2, "1357"), AgentCredentialStore.get(app, "uid-2"))
    }

    @Test fun `a reset replaces the saved PIN and a removed agent is forgotten`() {
        AgentCredentialStore.put(app, "uid-1", 0, "4826")
        AgentCredentialStore.put(app, "uid-1", 0, "9153")
        assertEquals(AgentCredentialStore.Creds(0, "9153"), AgentCredentialStore.get(app, "uid-1"))
        AgentCredentialStore.remove(app, "uid-1")
        assertNull(AgentCredentialStore.get(app, "uid-1"))
    }

    @Test fun `clearing forgets every agent`() {
        AgentCredentialStore.put(app, "uid-1", 0, "4826")
        AgentCredentialStore.put(app, "uid-2", 1, "1357")
        AgentCredentialStore.clear(app)
        assertNull(AgentCredentialStore.get(app, "uid-1"))
        assertNull(AgentCredentialStore.get(app, "uid-2"))
    }
}
