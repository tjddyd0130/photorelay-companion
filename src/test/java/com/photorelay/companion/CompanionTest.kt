package com.photorelay.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionTest {
    @Test
    fun dedupKeyMatchesS7Rule() =
        assertEquals("qZk-NkcGgWq6PiVxeFDCbJzQ2J0", DedupKey.of("abc".byteInputStream()))

    @Test
    fun keepRecentDays() {
        val day = 86_400_000L
        val now = 100 * day
        assertFalse(Cleaner.isOldEnough(takenMs = now - 2 * day, nowMs = now, keepDays = 3))
        assertTrue(Cleaner.isOldEnough(takenMs = now - 3 * day, nowMs = now, keepDays = 3))
        assertTrue(Cleaner.isOldEnough(takenMs = now, nowMs = now, keepDays = 0))
    }

    @Test
    fun onlyRelayedKeepsPhotosThatDidNotComeThroughS7() {
        val keys = mapOf("a.jpg" to "K1", "b.jpg" to "K2", "c.jpg" to "K3")
        val inCloud = setOf("K1", "K2")
        val relayed = setOf("K1")
        assertEquals(setOf("a.jpg"), Cleaner.selectTargets(keys, inCloud, relayed, onlyRelayed = true).keys)
        assertEquals(setOf("a.jpg", "b.jpg"), Cleaner.selectTargets(keys, inCloud, relayed, onlyRelayed = false).keys)
        // 예전 S7 앱처럼 relayed가 비어 오면 조건이 켜진 상태에서는 아무것도 지우지 않는다
        assertTrue(Cleaner.selectTargets(keys, inCloud, emptySet(), onlyRelayed = true).isEmpty())
    }

    @Test
    fun albumLabels() {
        assertEquals("카메라", Cleaner.albumLabel("DCIM/Camera/"))
        assertEquals("스크린샷", Cleaner.albumLabel("DCIM/Screenshots/"))
        assertEquals("KakaoTalk", Cleaner.albumLabel("Pictures/KakaoTalk/"))
    }

    @Test
    fun noAlbumsMessage() = assertEquals("정리할 앨범을 먼저 고르세요", CleanResult(noAlbums = true).text())

    @Test
    fun resultText() {
        val preview = CleanResult(inGooglePhotos = 10, inGooglePhotosBytes = 2L * 1073741824, notYetBackedUp = 4, preview = true)
        assertEquals("미리보기: 지울 수 있음 10개(2.00GB) · 아직 백업 안 됨 4개", preview.text())
        val real = CleanResult(deleted = 9, freedBytes = 1073741824, notYetBackedUp = 1, preview = false, timedOut = true)
        assertEquals("정리: 9개 삭제(1.00GB 확보) · 아직 백업 안 됨 1개 · 지문 계산은 다음 실행에서 이어서", real.text())
    }

    @Test
    fun releaseAssetName() {
        assertEquals(7, Updater.assetVersionCode("photorelay-companion-vc7.apk"))
        assertEquals(123, Updater.assetVersionCode("photorelay-companion-vc123.apk"))
        assertEquals(null, Updater.assetVersionCode("photorelay-companion-vc7.apk.sha1"))
        assertEquals(null, Updater.assetVersionCode("other-vc7.apk"))
    }
}
