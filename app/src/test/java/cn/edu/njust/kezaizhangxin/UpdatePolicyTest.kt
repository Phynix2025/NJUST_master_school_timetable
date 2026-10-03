package cn.edu.njust.kezaizhangxin

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun comparesNumericVersions() {
        assertTrue(UpdatePolicy.compareVersions("v2.10.0", "2.9.0") > 0)
        assertEquals(0, UpdatePolicy.compareVersions("v2.2.0", "2.2.0"))
        assertTrue(UpdatePolicy.compareVersions("2.1.0", "2.2.0") < 0)
    }
    @Test fun rejectsNonStableVersionTags() {
        for (value in listOf("v2.2.0-beta", "2.2", "latest", "2.2.0/evil")) {
            assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.compareVersions(value, "2.2.0") }
        }
    }
    @Test fun acceptsOnlyOwnHttpsReleaseAssets() {
        assertTrue(UpdatePolicy.validAssetUrl("https://github.com/Phynix2025/NJUST_master_school_timetable/releases/download/v2.2.0/update.json"))
        for (value in listOf(
            "http://github.com/Phynix2025/NJUST_master_school_timetable/releases/download/v2.2.0/x.apk",
            "https://github.com.evil.test/Phynix2025/NJUST_master_school_timetable/releases/download/v2.2.0/x.apk",
            "https://github.com/other/repo/releases/download/v2.2.0/x.apk",
            "https://user@github.com/Phynix2025/NJUST_master_school_timetable/releases/download/v2.2.0/x.apk",
            "https://github.com:444/Phynix2025/NJUST_master_school_timetable/releases/download/v2.2.0/x.apk"
        )) assertFalse(value, UpdatePolicy.validAssetUrl(value))
    }
    @Test fun requiresFullSha256() {
        assertTrue(UpdatePolicy.validHash("aB01".repeat(16)))
        assertFalse(UpdatePolicy.validHash("abc"))
        assertFalse(UpdatePolicy.validHash("g".repeat(64)))
    }
}
