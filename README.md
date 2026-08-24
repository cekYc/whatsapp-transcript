# SesYazı

SesYazı, WhatsApp sesli mesajlarını oynatmadan Türkçe metne çeviren, gizlilik
odaklı bir Android uygulamasıdır. WhatsApp paylaşım menüsünde bir hedef olarak
görünür; ses uygulama içinde ve cihaz üzerinde işlenir.

## Hazır APK ile kullanım

Çoğu güncel Android telefon için şu paketi kur:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

Eski 32-bit telefonlar için `app-armeabi-v7a-debug.apk`, mimarisinden emin
olmadığın cihazlar için `app-universal-debug.apk` kullanılabilir. Uygulama
Android 8.0 veya üzerini gerektirir.

İlk kullanım:

1. SesYazı’yı aç, kaliteyi seç ve **Modeli indir** düğmesine dokun. Önerilen
   **Dengeli** model yaklaşık 162 MB’tır ve bir kez indirilir.
2. WhatsApp’ta bir sesli mesaja uzun bas.
3. **Paylaş → SesYazı** yolunu seç.
4. **Metne çevir** düğmesine dokun; sonucu kopyala veya paylaş.

Android, dışarıdan indirilen APK için “bilinmeyen uygulamaları yükleme” izni
isteyebilir. Debug APK geliştirme amaçlı imzalı ve doğrudan kurulabilirdir.

## Gizlilik

- Ses veya transkript herhangi bir sunucuya gönderilmez.
- İnternet yalnızca konuşma modelini ilk kez indirmek için kullanılır.
- Paylaşılan ses uygulamanın özel geçici klasörüne kopyalanır ve başarılı
  transkripsiyondan sonra silinir.
- Uygulama yedeklemeyi ve cihazlar arası veri aktarımını kapatır.
- Model dosyaları boyut ve SHA-256 değerleriyle doğrulanır.

## Teknik yapı

- WhatsApp sesi Android `ACTION_SEND`/`content://` paylaşımıyla alınır.
- OGG/Opus ve cihazın desteklediği diğer sesler `MediaExtractor` +
  `MediaCodec` ile açılır.
- Ses mono 16 kHz PCM’e dönüştürülür.
- Ses seviyesi ve DC kayması transkripsiyondan önce normalize edilir.
- Silero VAD uzun sessizlikleri ayırır; konuşma parçaları modele ayrı ayrı
  verilir ve sonuçlar Türkçe metin kurallarıyla birleştirilir.
- Türkçe transkripsiyon `sherpa-onnx` üzerinde Whisper Tiny, Base veya Small
  Multilingual INT8 modeliyle tamamen yerel çalışır.

### Kalite seçenekleri

- **Hızlı (Tiny):** yaklaşık 105 MB; daha hızlı, temel doğruluk.
- **Dengeli (Base):** yaklaşık 162 MB; varsayılan ve önerilen seçenek.
- **Yüksek (Small):** yaklaşık 377 MB; daha doğru fakat daha yavaş ve daha çok
  bellek kullanır.

## Geliştirme

Gereksinimler:

- Android Studio veya JDK 17
- Android SDK 35

Komut satırı doğrulaması:

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Çıktılar `app/build/outputs/apk/debug/` altında oluşur.

## Bilinen sınırlar

- Bu çözüm WhatsApp mesaj balonunun içine yerleşmez; WhatsApp’ın paylaşım
  menüsü üzerinden açılır.
- Gürültü, üst üste konuşma, çok düşük ses ve özel isimler hâlâ hataya neden
  olabilir. Böyle kayıtlarda **Yüksek** kaliteyi deneyin.
- MVP sürümünde tek kayıt için sınır 15 dakika ve 200 MB’tır.
- İşlem süresi telefonun işlemcisine göre değişir; ses hiçbir zaman otomatik
  olarak oynatılmaz.

Üçüncü taraf bileşenler için [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
dosyasına bak.
