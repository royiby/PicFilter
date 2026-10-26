package com.picfilter.spike.data

/**
 * The rows [Db] hands back.
 *
 * They were nested inside Db, which made every caller write `Db.Candidate` and
 * made Db itself longer than it needed to be for types that carry no behaviour
 * and know nothing about SQLite. Out here they are what they are: the shapes
 * the queries return.
 */

data class ReviewRow(
    val id: Long, val uri: String, val decision: String, val reason: String,
    val faces: Int, val matched: Int, val indet: Int, val textFrac: Float,
    val dupeGroup: Long?,
)

data class FaceRow(
    val itemId: Long, val origPx: Int, val sharpness: Double,
    val yaw: Float, val roll: Float,
)

data class LabelRow(
    val itemId: Long, val label: String, val name: String?,
    val reason: String?, val faces: Int, val matched: Int, val indet: Int,
)

data class RescueRow(
    val id: Long, val uri: String, val reason: String, val faces: Int,
    val indet: Int, val nearName: String?, val nearSim: Float?,
)

data class PromoRow(
    val id: Long, val uri: String, val name: String?, val bytes: Long,
    val dateAdded: Long, val reason: String,
)

data class Candidate(val id: Long, val size: Int, val thumbs: List<String>)

data class StoredFace(
    val itemId: Long?, val thumb: String?, val origPx: Int, val vec: FloatArray,
)

data class PersonChip(
    val id: Long, val name: String, val thumbs: List<String>, val photos: Int,
)

data class QueueSizes(
    val verify: Int, val rescue: Int, val noface: Int, val junk: Int, val dupes: Int,
)
