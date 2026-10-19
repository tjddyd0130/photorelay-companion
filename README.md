# PhotoRelay 정리 (메인폰용)

집에 있는 루팅된 S7(PhotoRelay)이 "구글포토에 원본 화질로 백업됐다"고 확인한 사진만 메인폰에서 지우는 Android 앱입니다.

- 같은 Wi-Fi에서 S7과 6자리 코드로 연결합니다.
- 고른 앨범의 사진만, 지우기 직전에 지문(SHA-1)을 다시 확인한 뒤 지웁니다.
- 기본값은 미리보기(지울 대상만 계산)입니다.

## 자동 업데이트

앱이 하루 한 번 이 저장소의 [최신 릴리스](../../releases/latest)를 확인합니다.
릴리스에 `photorelay-companion-vc<versionCode>.apk`가 있고 versionCode가 설치된 것보다 크면 받아서 설치합니다.
Android는 같은 서명 키로 만든 APK만 업데이트로 받아들입니다.

## 빌드

```
./gradlew assembleDebug
```

minSdk 31 (Android 12+).
