# KitapTarama

Android'de kitap sayfası fotoğraflarını ve PDF/TIFF dosyalarını temizleyip okuyan, onaran ve PDF'e çeviren uygulama.

## Özellikler
- **Yükleme:** Klasör (kitap adı = klasör adı) veya tek tek dosya. JPEG, PNG, WEBP, BMP, GIF, HEIC, TIFF (çok sayfalı) ve PDF.
- **Temizleme:** Sayfayı bulup kırpar, eğriliği düzeltir, gölgeyi giderir. Renkli notalar korunur. Çıktı A4 (300 DPI).
- **Onarım:** Bozuk JPEG (SOI/EOI), TIFF (II*/MM*) ve PDF (%PDF- / %%EOF) başlık-sonluk baytlarını düzeltir; PDF'te gerekirse xref tablosunu yeniden kurar. Yapılanlar rapor olarak gösterilir.
- **Okuyucu:**
  - Normal Mod: üstte Ana sayfa, belge adı, Büyük Okuma, İşlemler menüsü; altta Sayfa X / Y geçişi.
  - Büyük Okuma: barlar gizlenir, kaydırarak sayfa çevrilir, iki parmakla yakınlaştırılır, sağ üstte Normal Mod düğmesi.
- **İşlemler:** Sayfa döndürme, silme, JPG olarak kaydetme, kitabı tek PDF olarak kaydetme.
- Kaydedilen dosyalar `İndirilenler/KitapOkuyucu/` klasörüne yazılır.

## Dosyalar
| Dosya | Görev |
|---|---|
| `KitapTaramaEtkinliği.kt` | Ekranlar, okuyucu, Büyük Okuma |
| `BookImporter.kt` | Klasör/dosya alma ve onarım akışı |
| `ImageSources.kt` | Onarım, TIFF çözücü, görsel çözme |
| `PageCleaner.kt` | Kırpma, eğrilik ve gölge temizleme |
| `PdfExporter.kt` | PDF/JPG/metin dışa aktarma |
| `build.sh`, `.github/workflows/apk.yml` | APK derleme (GitHub Actions) |

## Derleme
Depoya yüklenen her değişiklikte **Eylemler** sekmesindeki iş akışı APK'yı derler.

## Bilinen sınırlar
- Metin tanıma (OCR) bu sürümde kapalı; ek bir OCR kütüphanesi gerekir.
- TIFF: CCITT ve JPEG sıkıştırmalı ile döşemeli dosyalar desteklenmez.
- Onarım yalnızca başlık/sonluk ve PDF xref'ini düzeltir; içi bozuk veriyi kurtarmaz.
