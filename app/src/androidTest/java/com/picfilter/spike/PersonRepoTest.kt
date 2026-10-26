package com.picfilter.spike

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picfilter.spike.data.Db
import com.picfilter.spike.data.HomeRepo
import com.picfilter.spike.data.OnboardingRepo
import com.picfilter.spike.data.PersonRepo
import com.picfilter.spike.data.ReviewRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The person screen's logic, with no database anywhere.
 *
 * This is what the repository boundary bought. Before it, every ViewModel held
 * a `Db`, so the only possible fake was a real SQLite file: these tests had to
 * create one, seed it, and delete it afterwards, and the thing under test was
 * never the ViewModel alone.
 *
 * [PersonRepo] is two methods, so the fake below is twelve lines and answers
 * from a list. What fails here can only be the ViewModel.
 *
 * Still instrumented rather than JVM, and the reason is worth knowing: the
 * ViewModel is an `AndroidViewModel` and `remove()` reaches the file system
 * through `Clusterer.personFaces`. The repository narrowed the data dependency
 * to nothing; the Android dependency is still there. **A seam removes the
 * dependency you drew it around and no others.**
 */
@RunWith(AndroidJUnit4::class)
class PersonRepoTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Twelve lines, and not a byte of SQLite. */
    private class FakePersonRepo(
        private val photos: MutableList<Pair<Long, String>>,
    ) : PersonRepo {
        var removedId: Long? = null
        var closed = false
        override fun photosOf(personId: Long, limit: Int) = photos.toList()
        override fun removePerson(personId: Long): Int {
            removedId = personId
            return photos.size
        }
        override fun close() { closed = true }
    }

    private fun depsFor(repo: PersonRepo) = object : Deps {
        override fun openDb(): Db = throw AssertionError("the screen must not open a database")
        override fun personRepo(): PersonRepo = repo
        override fun homeRepo(): HomeRepo = throw AssertionError("not this screen")
        override fun reviewRepo(): ReviewRepo = throw AssertionError("not this screen")
        override fun onboardingRepo(): OnboardingRepo = throw AssertionError("not this screen")
    }

    private fun vmFor(repo: PersonRepo, id: Long = 7L) = PersonViewModel(
        app,
        SavedStateHandle(mapOf("person_id" to id, "person_name" to "alice")),
        depsFor(repo),
    )

    private fun <T> await(timeoutMs: Long = 3_000, read: () -> T?): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            read()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("value never arrived")
    }

    /**
     * openDb() throws in this fake, so this passing is itself the assertion
     * that the screen no longer reaches past its own interface.
     */
    @Test fun the_screen_loads_its_photos_without_touching_a_database() {
        val repo = FakePersonRepo(mutableListOf(1L to "content://a", 2L to "content://b"))
        val vm = vmFor(repo)
        val photos = await { vm.photos.value }
        assertEquals(2, photos.size)
        assertEquals("content://a", photos[0].second)
    }

    /** null means loading and empty means none; the screen says different things. */
    @Test fun an_unknown_person_is_empty_rather_than_loading() {
        val vm = vmFor(FakePersonRepo(mutableListOf()), id = -1L)
        assertEquals(emptyList<Pair<Long, String>>(), await { vm.photos.value })
    }

    @Test fun remove_asks_for_this_person_and_reports_done() {
        val repo = FakePersonRepo(mutableListOf(1L to "content://a"))
        val vm = vmFor(repo)
        await { vm.photos.value }

        vm.remove()

        assertTrue("removed flag", await { vm.removed.value.takeIf { it } })
        assertEquals("and for the right person", 7L, repo.removedId)
    }

    /** The handle the ViewModel opened is the handle it closes. */
    @Test fun clearing_the_view_model_closes_the_repository() {
        val repo = FakePersonRepo(mutableListOf())
        val store = ViewModelStore()
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vmFor(repo) as T
        })[PersonViewModel::class.java]
        await { vm.photos.value }

        store.clear()   // what the system does when the screen is finished

        assertTrue("repository closed", await { repo.closed.takeIf { it } })
    }
}
