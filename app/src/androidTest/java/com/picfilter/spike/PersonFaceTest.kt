package com.picfilter.spike

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picfilter.spike.data.Db
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A row is not a file.
 *
 * Face crops live in the app's own storage while the database holds their
 * paths, so clearing app data or reinstalling takes the files and leaves the
 * rows. This has now caused the same failure twice: once as candidate cards
 * asking "do you know this person?" with no person on them, and once as the
 * people row falling back to initials while every piece of code that could
 * have repaired it believed a face was already there.
 *
 * Whoever hands out a path has to have checked it, because the callers cannot
 * tell a path from a picture.
 */
@RunWith(AndroidJUnit4::class)
class PersonFaceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "personface-test.db"
    private lateinit var db: Db
    private lateinit var scratch: File

    @Before fun setUp() {
        ctx.deleteDatabase(dbName)
        db = Db(ctx, dbName)
        scratch = File(ctx.cacheDir, "personface-test").apply { mkdirs() }
    }

    @After fun tearDown() {
        db.close()
        ctx.deleteDatabase(dbName)
        scratch.deleteRecursively()
    }

    /** A person with one cluster, whose face crops are at [paths]. */
    private fun personWithCrops(name: String, paths: List<String>): Long {
        val pid = db.upsertPerson(name)
        val cid = db.addCluster(paths.size, FloatArray(192) { 0.1f })
        for ((i, p) in paths.withIndex()) {
            db.addClusterFace(cid, 100L + i, p, 120 - i, FloatArray(192) { 0.1f })
        }
        db.setClusterStatus(cid, "NAMED", pid)
        return pid
    }

    private fun realCrop(name: String): String =
        File(scratch, name).apply { writeBytes(ByteArray(8) { 0 }) }.absolutePath

    @Test fun a_crop_still_on_disk_is_offered() {
        personWithCrops("alice", listOf(realCrop("a1.jpg"), realCrop("a2.jpg")))
        val chip = db.enrolledPeople().single { it.name == "alice" }
        assertEquals(2, chip.thumbs.size)
    }

    @Test fun a_path_whose_file_is_gone_is_not() {
        // Exactly the state a reinstall leaves: rows intact, files taken.
        personWithCrops("bob", listOf("${scratch.absolutePath}/gone1.jpg",
                                      "${scratch.absolutePath}/gone2.jpg"))
        val chip = db.enrolledPeople().single { it.name == "bob" }
        assertTrue("a dead path must not be offered as a face", chip.thumbs.isEmpty())
    }

    @Test fun the_survivors_are_kept_when_only_some_files_are_gone() {
        personWithCrops("cara", listOf(realCrop("c1.jpg"),
                                       "${scratch.absolutePath}/gone.jpg",
                                       realCrop("c2.jpg")))
        val chip = db.enrolledPeople().single { it.name == "cara" }
        assertEquals(2, chip.thumbs.size)
        assertTrue(chip.thumbs.all { File(it).exists() })
    }

    @Test fun an_empty_thumb_list_is_what_marks_someone_as_needing_repair() {
        // The home screen decides who to rebuild by asking whether anyone has
        // no face left. That decision is only correct while this list contains
        // files rather than rows -- when it did not, every person looked
        // repaired and nobody was.
        personWithCrops("dana", listOf("${scratch.absolutePath}/missing.jpg"))
        val needsRepair = db.enrolledPeople().filter { it.thumbs.isEmpty() }
        assertEquals(1, needsRepair.size)
        assertEquals("dana", needsRepair.single().name)
    }
}
