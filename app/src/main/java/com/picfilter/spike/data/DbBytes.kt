package com.picfilter.spike.data

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An embedding in and out of a BLOB column.
 *
 * Little-endian on purpose and not negotiable: the stored bytes outlive any one
 * build, so changing the order here would silently reinterpret every centroid
 * already sitting on a phone.
 *
 * These were in Db's companion, which is where things go when nobody decides
 * where they belong. They turn floats into bytes and know nothing about SQLite.
 */

internal fun floatsToBytes(v: FloatArray): ByteArray {
    val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (x in v) b.putFloat(x)
    return b.array()
}

internal fun bytesToFloats(a: ByteArray): FloatArray {
    val b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(a.size / 4) { b.getFloat() }
}
