# Kitap Tarama modülü – AliHoca entegrasyonu

Mevcut arayüze dokunmaz: ayrı bir Activity'dir. Paket adı `tr.alihoca.turku.yeni` varsayıldı;
farklıysa her dosyanın `package` satırını ve manifest yolunu değiştirin.

## 1) Dosyalar
`app/src/main/java/tr/alihoca/turku/yeni/scan/` altındaki 5 dosyayı projeye kopyalayın.

## 2) build.gradle (app) – dependencies
    implementation 'androidx.documentfile:documentfile:1.0.1'
    implementation 'androidx.exifinterface:exifinterface:1.3.7'
(AppCompat zaten projede olmalı. minSdk en az 29 olmalı – PDF'i İndirilenler'e MediaStore ile yazıyor.)

## 3) AndroidManifest.xml
    <application ... android:largeHeap="true"> <!-- büyük sayfa görselleri için -->
        <activity android:name=".scan.BookScanActivity" android:exported="false" android:label="Kitap Tarama" />
Depolama izni gerekmez (klasör Android'in klasör seçicisiyle verilir).

## 4) Açmak için (istediğiniz bir yerden)
    startActivity(Intent(this, tr.alihoca.turku.yeni.scan.BookScanActivity::class.java))

## 5) Çıktı
`filesDir/books/<KitapAdı>/pages/sayfa_0001.jpg ...` (A4, 2480x3508, renkli notalar korunur).
Mevcut sayfa görüntüleyicinize bu klasörü verebilirsiniz.

## Formatlar
JPEG, PNG, WEBP, BMP, GIF, HEIC: desteklenir. PDF: her sayfa 2480 px genişlikte çizilir (PdfRenderer).
TIFF: Android bunu kendi çözemez. Bir TIFF kütüphanesi ekleyip şöyle bağlayın:
    TiffDecoder.decode = { ctx, uri -> /* kütüphane ile Bitmap üret */ }
Bağlanmazsa TIFF dosyaları "okunamadı" olarak sayılır, diğerleri etkilenmez.

## Notlar
- Dosyalar doğal sırayla okunur (1 (2) < 1 (10)); alt klasörler okunmaz.
- Aynı adlı kitap tekrar yüklenirse eskisinin yerine yazılır.
- Sayfa başına ~1-2 sn sürer; ekran işlem sırasında açık tutulur.
- Derleme sonrası: zipalign -p -f 4, apksigner V1+V2+V3 imzası sizin mevcut yönteminizle.
