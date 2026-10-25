package com.picfilter.spike

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the first-run flag, which crashed the app for every new user.
 *
 * A ViewModel's SavedStateHandle is seeded from the launching intent's extras,
 * keyed by the extra's own name. `S_FIRST` is "first_run" and the launcher sent
 * `putExtra("first_run", "1")`, so the handle held a String where the
 * constructor cast to Boolean: ClassCastException inside
 * OnboardingViewModel.<init>, before onCreate had drawn anything.
 *
 * It only fired when the extra was present, which is the genuine first run and
 * nothing else -- so an install with anyone already enrolled never took the
 * path, and the only people who met it were the ones who had just downloaded
 * the app.
 *
 * Both halves are fixed: the launchers pass a Boolean, and this reader no
 * longer assumes they did. The test is on the reader, because that is the half
 * that has to survive a launcher nobody has written yet, and adb can only ever
 * send a string.
 */
class FirstRunFlagTest {

    @Test fun a_real_boolean_passes_through() {
        assertTrue(firstRunFlag(true))
        assertFalse(firstRunFlag(false))
    }

    /** What the launcher actually sent, and what adb can only ever send. */
    @Test fun the_string_that_caused_the_crash_now_reads_as_true() {
        assertTrue(firstRunFlag("1"))
        assertTrue(firstRunFlag("true"))
        assertTrue(firstRunFlag("TRUE"))
    }

    @Test fun strings_that_do_not_mean_yes_are_false() {
        assertFalse(firstRunFlag("0"))
        assertFalse(firstRunFlag("false"))
        assertFalse(firstRunFlag(""))
        assertFalse(firstRunFlag("yes"))   // not a spelling we promise to accept
    }

    @Test fun absent_means_not_a_first_run() {
        assertFalse(firstRunFlag(null))
    }

    /**
     * The property that matters more than any single case: nothing throws.
     * The crash was not a wrong answer, it was an exception in a constructor,
     * and no input may reintroduce that.
     */
    @Test fun nothing_throws_whatever_arrives() {
        for (v in listOf<Any?>(
            null, true, false, "1", "0", "", "true", "no", 1, 0, 2L, 1.0,
            'x', listOf(1), arrayOf("1"), Any(),
        )) {
            firstRunFlag(v)   // must not throw
        }
    }

    @Test fun a_number_follows_the_same_rule_as_the_string() {
        assertTrue(firstRunFlag(1))
        assertFalse(firstRunFlag(0))
        assertFalse(firstRunFlag(2))
    }
}
