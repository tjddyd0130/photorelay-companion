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

    @Test
    fun keepDaysUsesLaterOfTakenAndAdded() {
        val day = 86_400_000L
        val now = 100 * day
        // 촬영일은 오래됐지만 오늘 폰에 들어온 사진은 "최근"으로 본다
        assertEquals(now, Cleaner.recentOf(now - 50 * day, now))
        // 촬영일이 없으면(0) 추가 시각
        assertEquals(now - 5 * day, Cleaner.recentOf(0, now - 5 * day))
        assertTrue(Cleaner.isOldEnough(Cleaner.recentOf(now - 50 * day, now - 10 * day), now, 3))
        assertTrue(!Cleaner.isOldEnough(Cleaner.recentOf(now - 50 * day, now), now, 3))
    }

    @Test
    fun signatureMatchesKnownVector() {
        // RFC 4231 test case 2: key "Jefe", data "what do ya want for nothing?"
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", Sig.hmacHex("Jefe", "what do ya want for nothing?"))
        assertTrue(Sig.matches("abcd", "abcd"))
        assertTrue(!Sig.matches("abcd", "abce"))
        assertTrue(!Sig.matches("abcd", null))
        assertTrue(!Sig.matches("abcd", "abc"))
    }

    @Test
    fun s7WatchDecisions() {
        val h = 3_600_000L
        val now = 100 * h
        // 최근 하트비트: 조용
        assertEquals(null, S7Watch.decide(now - h, now, false, 0).notify)
        // 4시간째 없음: 알림, down
        val d1 = S7Watch.decide(now - 4 * h, now, false, 0)
        assertTrue(d1.notify!!.contains("4시간째") && d1.down)
        // 이미 알렸으면 12시간 안에는 다시 안 알림
        assertEquals(null, S7Watch.decide(now - 5 * h, now + h, true, now).notify)
        // 12시간이 지나면 다시 알림
        assertTrue(S7Watch.decide(now - 20 * h, now + 13 * h, true, now).notify != null)
        // 돌아오면 정상 알림
        val back = S7Watch.decide(now - 10 * 60_000, now, true, now - h)
        assertTrue(back.notify!!.contains("정상") && !back.down)
        // ntfy에 아무것도 없으면(12시간 넘게) 알림
        assertTrue(S7Watch.decide(null, now, false, 0).notify!!.contains("12시간"))
    }
}
