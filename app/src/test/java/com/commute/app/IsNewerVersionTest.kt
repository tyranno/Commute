package com.commute.app

import com.commute.app.update.isNewerVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [isNewerVersion] drives whether the 앱 업데이트 card offers to install a fetched GitHub
 * release — these cover the comparison rules it leans on: numeric component-by-component
 * (not string) ordering, a "v" tag prefix, and malformed input degrading gracefully. */
class IsNewerVersionTest {

    @Test
    fun `a higher patch version is newer`() {
        assertTrue(isNewerVersion("0.2.1", "0.2.0"))
    }

    @Test
    fun `an equal version is not newer`() {
        assertFalse(isNewerVersion("0.2.0", "0.2.0"))
    }

    @Test
    fun `an older version is not newer`() {
        assertFalse(isNewerVersion("0.1.9", "0.2.0"))
    }

    @Test
    fun `a leading v tag prefix is ignored`() {
        assertTrue(isNewerVersion("v0.3.0", "0.2.0"))
    }

    @Test
    fun `double-digit components compare numerically, not lexically`() {
        assertTrue(isNewerVersion("0.10.0", "0.9.0"))
    }

    @Test
    fun `a missing trailing component compares as zero`() {
        assertFalse(isNewerVersion("1.2", "1.2.0"))
        assertTrue(isNewerVersion("1.3", "1.2.9"))
    }

    @Test
    fun `a non-numeric component degrades to zero rather than throwing`() {
        assertFalse(isNewerVersion("0.2.0-beta", "0.2.0"))
    }
}
