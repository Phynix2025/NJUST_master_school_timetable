package cn.edu.njust.kezaizhangxin

/** No Android dependency: shared release validation rules are unit-testable. */
internal object UpdatePolicy {
    fun compareVersions(left: String, right: String): Int {
        fun parts(value: String): List<Long> {
            require(Regex("v?\\d+\\.\\d+\\.\\d+").matches(value)) { "版本号格式不支持" }
            return value.removePrefix("v").split('.').map { it.toLong() }
        }
        return parts(left).zip(parts(right)).firstOrNull { it.first != it.second }
            ?.let { it.first.compareTo(it.second) } ?: 0
    }

    fun validHash(value: String) = Regex("[a-fA-F0-9]{64}").matches(value)

    fun validAssetUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.fragment == null && uri.query == null &&
            uri.path.startsWith("/Phynix2025/NJUST_master_school_timetable/releases/download/")
    }.getOrDefault(false)
}
