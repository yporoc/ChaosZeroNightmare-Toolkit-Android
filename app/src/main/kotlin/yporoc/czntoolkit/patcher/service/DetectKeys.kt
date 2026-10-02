package yporoc.czntoolkit.patcher.service

/** detect() 返回 Bundle 的键名约定（服务端写入、UI 端读取共用）。 */
object DetectKeys {
    const val OK = "ok"
    const val ERROR = "error"
    const val PATH = "path"
    const val MANIFEST_EXISTS = "manifestExists"
    const val PART_EXISTS = "partExists"
    const val ETAG_EXISTS = "etagExists"
    const val MANIFEST_SIZE = "manifestSize"
    const val PART_SIZE = "partSize"
    const val ETAG_SIZE = "etagSize"
    const val MANIFEST_OK = "manifestOk"
    const val MANIFEST_ERROR = "manifestError"
    const val BUILD = "build"
    const val PART_COUNT = "partCount"
    const val FILE_COUNT = "fileCount"
    const val TEXTDB_FOUND = "textdbFound"
    const val TEXTDB_PART = "textdbPart"
    const val ETAG_MATCH = "etagMatch"
    const val PART_FOOTER_OK = "partFooterOk"
    const val WRITE_OK = "writeOk"
    const val BACKUP_EXISTS = "backupExists"
    const val SUMMARY = "summary"
    const val CONVERTED = "converted"
    const val LOG = "log"
}
