package com.hortus1556.exilum.core

object Constants {
    const val ROOM_DATABASE_NAME: String = "xmp_database"
    const val HTTP_BASE_URL: String = "https://api.modarchive.org"
    const val TMA_BASE_URL: String = "https://modarchive.org/"
    // Formats The Mod Archive lists that nothing here plays. ahx and hvl
    // left when the Hively replayer came in, mo3 when libopenmpt was built
    // with minimp3 and stb_vorbis to unpack its samples.
    val UNSUPPORTED_EXTENSIONS = emptySet<String>()
    val SKIP_EXTENSIONS = setOf(
        "txt", "pdf", "doc", "docx", "rtf", "nfo", "diz", "me",
        "jpg", "jpeg", "png", "gif", "bmp", "webp",
        "zip", "rar", "7z", "gz", "tar",
        "mp4", "mkv", "mov", "avi", "webm",
        "xml", "json", "html", "htm", "css", "js",
    )
}
