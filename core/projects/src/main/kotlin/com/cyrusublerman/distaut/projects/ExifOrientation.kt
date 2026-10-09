package com.cyrusublerman.distaut.projects

/** Linear component; Bitmap.createBitmap translates the transformed bounds back to the origin. */
object ExifOrientation {
    fun matrix(orientation: Int): FloatArray = when (orientation) {
        2 -> floatArrayOf(-1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
        3 -> floatArrayOf(-1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
        4 -> floatArrayOf(1f,0f,0f, 0f,-1f,0f, 0f,0f,1f)
        5 -> floatArrayOf(0f,1f,0f, 1f,0f,0f, 0f,0f,1f)
        6 -> floatArrayOf(0f,-1f,0f, 1f,0f,0f, 0f,0f,1f)
        7 -> floatArrayOf(0f,-1f,0f, -1f,0f,0f, 0f,0f,1f)
        8 -> floatArrayOf(0f,1f,0f, -1f,0f,0f, 0f,0f,1f)
        else -> floatArrayOf(1f,0f,0f, 0f,1f,0f, 0f,0f,1f)
    }
}
