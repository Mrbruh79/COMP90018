package com.example.blap.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileUrlTest {
    @Test
    fun aMissingSchemeIsAdded() {
        assertEquals("https://alexi.co", ProfileUrl.normalize("alexi.co"))
        assertEquals("https://www.linkedin.com/in/alexi", ProfileUrl.normalize(" www.linkedin.com/in/alexi "))
    }

    @Test
    fun anExistingSchemeIsKept() {
        assertEquals("https://x.com/alexi", ProfileUrl.normalize("https://x.com/alexi"))
        assertEquals("http://alexi.co", ProfileUrl.normalize("http://alexi.co"))
    }

    @Test
    fun valuesThatCannotBeUrlsAreLeftAsTyped() {
        assertEquals("hello world", ProfileUrl.normalize("  hello world  "))
        assertEquals("@alexi", ProfileUrl.normalize("@alexi"))
        assertEquals("", ProfileUrl.normalize("   "))
    }

    @Test
    fun onlyUrlLikeValuesAreOpenable() {
        assertTrue(ProfileUrl.isOpenable("alexi.co"))
        assertTrue(ProfileUrl.isOpenable("https://x.com/alexi"))
        assertFalse(ProfileUrl.isOpenable("hello world"))
        assertFalse(ProfileUrl.isOpenable("@alexi"))
        assertFalse(ProfileUrl.isOpenable(""))
    }
}
