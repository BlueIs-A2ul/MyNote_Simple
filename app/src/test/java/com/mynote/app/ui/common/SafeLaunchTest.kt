package com.mynote.app.ui.common

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SafeLaunchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun capturesNonCancellationFailure() = runTest(dispatcher) {
        val vm = object : ViewModel() {}
        var caught: Throwable? = null

        vm.launchSafely(onError = { caught = it }) {
            throw IllegalStateException("db down")
        }
        advanceUntilIdle()

        assertEquals("db down", caught?.message)
    }

    @Test
    fun cancellationIsNotReportedAsBusinessError() = runTest(dispatcher) {
        val vm = object : ViewModel() {}
        var caught = false

        val job = vm.launchSafely(onError = { caught = true }) {
            throw CancellationException("cancelled")
        }
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertFalse(caught)
    }
}
