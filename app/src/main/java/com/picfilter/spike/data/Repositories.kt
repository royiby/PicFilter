package com.picfilter.spike.data

/**
 * What each screen is allowed to ask the database for.
 *
 * Splitting `Db.kt` into files made it readable; it did not make it smaller
 * from a caller's point of view. Every ViewModel still held a `Db` and could
 * reach all ninety-odd queries, so "what does the person screen touch?" could
 * only be answered by reading it, and a fake for a test had to be a real
 * database because nothing smaller would do.
 *
 * These are the answer, and the widths are the point:
 *
 *  - [PersonRepo] is two methods. The screen shows someone's photos and
 *    removes them; that is genuinely all of it, and `PersonScreenLogicTest`
 *    drives it with twelve lines of in-memory fake and no SQLite at all.
 *  - [ReviewRepo] is eleven, and one of them only exists because drawing this
 *    line found a `writableDatabase.execSQL` living in a ViewModel.
 *  - [HomeRepo] is sixteen, which is a fair description of the home screen: it
 *    is the screen that shows everything.
 *
 * Each extends [AutoCloseable] because the implementation owns a database
 * handle, and the ViewModel that opened it closes it on [DbQueue] after the
 * writes it queued.
 */

interface PersonRepo : AutoCloseable {
    fun photosOf(personId: Long, limit: Int = 500): List<Pair<Long, String>>
    fun removePerson(personId: Long): Int
}

interface OnboardingRepo : AutoCloseable {
    fun candidates(limit: Int, thumbsEach: Int): List<Candidate>
    fun skippedClusters(minSize: Int): List<Candidate>
    fun setClusterStatus(clusterId: Long, status: String, personId: Long?)
    fun enrolledCount(): Int
}

interface ReviewRepo : AutoCloseable {
    fun goldenQueue(perReason: Int): List<ReviewRow>
    fun rescueQueue(
        reasons: List<String> = listOf("INDETERMINATE_FACE", "FEW_FACES_NO_MATCH"),
        limit: Int = -1,
    ): List<RescueRow>
    fun junkQueue(limit: Int = -1): List<RescueRow>
    fun promotedForReview(limit: Int = -1): List<RescueRow>
    fun dupeGroups(): List<Pair<Long, List<Pair<Long, String>>>>
    fun putLabel(itemId: Long, label: String, note: String? = null)
    fun deleteLabel(itemId: Long)
    fun putDupeVerdict(groupId: Long, verdict: String)
    fun deleteDupeVerdict(groupId: Long)
    fun labelCounts(): List<Pair<String, Int>>
    fun dupeVerdictCounts(): List<Pair<String, Int>>
}

interface HomeRepo : AutoCloseable {
    fun promotionCount(): Int
    fun notSavedCount(): Int
    fun undecidedCount(): Int
    fun enrolledPeople(thumbsEach: Int = 6): List<PersonChip>
    fun enrolledCount(): Int
    fun queueSizes(): QueueSizes
    fun junkQueue(limit: Int = -1): List<RescueRow>
    fun dupePreview(limit: Int): List<String>
    fun promotedForReview(limit: Int = -1): List<RescueRow>
    fun rescueQueue(
        reasons: List<String> = listOf("INDETERMINATE_FACE", "FEW_FACES_NO_MATCH"),
        limit: Int = -1,
    ): List<RescueRow>
    fun matchedItemsFor(personId: Long, limit: Int): List<Pair<Long, String>>
    fun autoSaveWanted(): Boolean?
    fun setAutoSaveWanted(on: Boolean)
    fun crashReportsWanted(): Boolean?
    fun setCrashReportsWanted(on: Boolean)
}

// ---------------------------------------------------------------- Db-backed

/**
 * The real implementations: thin, and deliberately so.
 *
 * Each one owns the [Db] it was given and closes it. There is no logic here —
 * a repository that transforms on its way through is a second place for a bug
 * to live, and the queries already say what they mean.
 */

internal abstract class DbRepo(protected val db: Db) : AutoCloseable {
    override fun close() = db.close()
}

internal class DbPersonRepo(db: Db) : DbRepo(db), PersonRepo {
    override fun photosOf(personId: Long, limit: Int) = db.photosOf(personId, limit)
    override fun removePerson(personId: Long) = db.removePerson(personId)
}

internal class DbOnboardingRepo(db: Db) : DbRepo(db), OnboardingRepo {
    override fun candidates(limit: Int, thumbsEach: Int) = db.candidates(limit, thumbsEach)
    override fun skippedClusters(minSize: Int) = db.skippedClusters(minSize)
    override fun setClusterStatus(clusterId: Long, status: String, personId: Long?) =
        db.setClusterStatus(clusterId, status, personId)
    override fun enrolledCount() = db.enrolledCount()
}

internal class DbReviewRepo(db: Db) : DbRepo(db), ReviewRepo {
    override fun goldenQueue(perReason: Int) = db.goldenQueue(perReason)
    override fun rescueQueue(reasons: List<String>, limit: Int) = db.rescueQueue(reasons, limit)
    override fun junkQueue(limit: Int) = db.junkQueue(limit)
    override fun promotedForReview(limit: Int) = db.promotedForReview(limit)
    override fun dupeGroups() = db.dupeGroups()
    override fun putLabel(itemId: Long, label: String, note: String?) =
        db.putLabel(itemId, label, note)
    override fun deleteLabel(itemId: Long) = db.deleteLabel(itemId)
    override fun putDupeVerdict(groupId: Long, verdict: String) =
        db.putDupeVerdict(groupId, verdict)
    override fun deleteDupeVerdict(groupId: Long) = db.deleteDupeVerdict(groupId)
    override fun labelCounts() = db.labelCounts()
    override fun dupeVerdictCounts() = db.dupeVerdictCounts()
}

internal class DbHomeRepo(db: Db) : DbRepo(db), HomeRepo {
    override fun promotionCount() = db.promotionCount()
    override fun notSavedCount() = db.notSavedCount()
    override fun undecidedCount() = db.undecidedCount()
    override fun enrolledPeople(thumbsEach: Int) = db.enrolledPeople(thumbsEach)
    override fun enrolledCount() = db.enrolledCount()
    override fun queueSizes() = db.queueSizes()
    override fun junkQueue(limit: Int) = db.junkQueue(limit)
    override fun dupePreview(limit: Int) = db.dupePreview(limit)
    override fun promotedForReview(limit: Int) = db.promotedForReview(limit)
    override fun rescueQueue(reasons: List<String>, limit: Int) = db.rescueQueue(reasons, limit)
    override fun matchedItemsFor(personId: Long, limit: Int) = db.matchedItemsFor(personId, limit)
    override fun autoSaveWanted() = db.autoSaveWanted()
    override fun setAutoSaveWanted(on: Boolean) = db.setAutoSaveWanted(on)
    override fun crashReportsWanted() = db.crashReportsWanted()
    override fun setCrashReportsWanted(on: Boolean) = db.setCrashReportsWanted(on)
}
