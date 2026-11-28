# TV Listesi — Uzaktan yönetilen Android TV/telefon oynatıcısı

Kanal listesi uygulamanın içinde sabit değildir. Uygulama her açılışta **sunucudaki bir
JSON veya M3U adresini** indirir, listeyi önbelleğe alır ve kanalları **Media3 / ExoPlayer**
ile oynatır. Listeyi güncellemek için sunucudaki dosyayı değiştirmeniz yeterlidir;
uygulamayı yeniden yayınlamanız gerekmez.

## Özellikler

- Sunucudan yönetilen liste: JSON (`channels.json`) **veya** M3U/M3U8 desteği
  (HLS master playlist'leri — `#EXT-X-STREAM-INF` — tek kanal olarak açılır;
  göreli `.m3u8` adresleri liste adresine göre çözümlenir)
- **Yerleşik liste:** ailantus/TeVe M3U listesi APK'nın içine gömülüdür; listede
  her zaman görünür, silinemez ve ağ yokken de çalışır
- Açılışta otomatik güncelleme (kapatılabilir) + elle yenileme
- Ağ yoksa son başarılı listenin önbellekten gösterilmesi
- Kanal arama ve grup filtresi
- En fazla **10 listeyi** adlandırarak kaydetme ve aralarında tek dokunuşla geçiş
- Harici oynatıcı desteği (VLC, MX Player vb.)
- **Doğrudan yayın linki açma:** ⋮ → **Yayın adresi aç** ile HLS/DASH/MP4 adresini kanal listesine gerek kalmadan oynatma; uzantısız HLS film bağlantıları (`.../vs/tt.../`) da desteklenir
- **M3U film listeleri:** tarayıcı/Telegram'dan bir `.m3u` bağlantısına dokunduğunuzda listedeki filmler etkin liste olarak yüklenir (ör. `power-sinema.m3u`)
- **APK'yı Telegram'da paylaşma:** ⋮ → **APK'yı paylaş** kurulu sürümü, güncelleme uyarısındaki **Telegram'da paylaş** ise indirilen yeni sürümü gönderir (Telegram yoksa paylaşım seçicisi açılır)
- **Geniş biçim desteği:** HLS (`.m3u8`), DASH (`.mpd`), Smooth Streaming (`.ism/Manifest`) ve bilinen tüm video/ses uzantıları (`.mp4`, `.mkv`, `.webm`, `.ts`, `.avi`, `.flv`, `.mp3`, `.m4a`, `.aac`, `.flac`, `.ogg`, `.wav` vb.); uzantısız HLS bağlantıları da algılanır. Bilinmeyen biçimlerde MIME zorlanmaz, içerik ExoPlayer tarafından sınanır
- **Kaydırarak kanal değiştirme**, parlaklık/ses ayarı, oynat/duraklat; destekleyen yayınlarda sürüklenebilir zaman çizgisi
- Oynatıcıda **sağ tarafta kanal listesi**: geçerli kanal vurgulanır, dokunarak veya
  D-pad ile kanal değiştirilir; liste düğmesiyle gizlenip gösterilebilir
- Oynatıcıda üst/alt çubuklar, sağdaki kanal paneli ve sistem çubukları **3 saniye** sonra gizlenir; kanal listesi ekranının üst çubuğu ve filtre satırı her zaman görünür kalır
- Hem listede hem oynatıcıda **ekranı döndürme** düğmesi (yatay ↔ dikey)
- Oynatıcıda **ekran boyutu** düğmesi: Sığdır → Kırp → Esnet görüntü modları
- **DLNA/UPnP**: açık kanalı ağdaki DLNA oynatıcılarına (akıllı TV, medya kutusu) gönderme
  ve DLNA medya sunucularındaki (NAS, bilgisayar) içerikleri gezip oynatma
- **Telefon → TV yansıtma:** aynı Wi-Fi'deki iki uygulama birbirini otomatik bulur;
  6 haneli eşleştirme koduyla telefondaki kanal TV kutusunda oynatılır (DLNA gerekmez).
  Yansıtma sırasında telefondan **ses, ileri/geri sarma ve oynat/duraklat** kontrolü de kullanılır
- Telefon, tablet ve Android TV (Leanback başlatıcı + D-pad odak desteği)
- Kanal logosu yükleme, uzun basınca yayın adresini kopyalama
- Koyu tema, harici görsel/JSON kütüphanesi gerektirmeyen sade bağımlılıklar
- **Toplu link kontrolü:** Etkin listedeki tüm yayın adreslerini eşzamanlı sınar;
  çalışan/hatalı filtresi ve tek dokunuşla hatalıları ayıklayıp yerel liste oluşturma
- **Hızlı açılış:** aynı sürümde APK tekrar indirilmez, aynı liste 15 dakika
  içinde yeniden indirilmez, arama yazarken filtreleme geciktirilir

## İndir

Hazır imzalı APK: [TvListesi 2.0.31 sürümü](https://github.com/ailantus/TvListesi/releases/latest)
(`TvListesi-2.0.31.apk`, versionCode 42). Uygulama açılışta bu sürümü kontrol eder
ve yeni sürüm varsa kurulumu teklif eder.

## Hızlı başlangıç

### 1. Örnek sunucuyu çalıştırın

```bash
cd sunucu
python3 baslat.py
```

Çıktıda iki adres görünür:

```
Bilgisayar : http://localhost:8000/channels.json
Telefon/TV : http://192.168.1.25:8000/channels.json
```

Telefon/TV ile bilgisayar **aynı Wi-Fi ağında** olmalıdır.

### 2. Uygulamayı derleyip kurun

Android Studio ile: `TvListesi` klasörünü açın, JDK 17 seçin, **Run**.
Komut satırı ile:

```bash
export JAVA_HOME=/yol/jdk-17
./gradlew assembleRelease
adb connect <BOX-IP>:5555
# Eski debug imzalı sürümü kaldırın (imza değiştiği için güncelleme reddedilir):
adb uninstall com.example.tvlistesi
adb install -r app/build/outputs/apk/release/app-release.apk
```

Hazır imzalı dosyayı GitHub sürümlerinden indirin:
https://github.com/ailantus/TvListesi/releases/latest
(imzalı release APK, alias `dlna_tv_release`). Yerel derleme çıktısı
`app/build/outputs/apk/release/app-release.apk` yolundadır. Play Protect, Play
Store dışından gelen her APK için yine de "doğrulanmamış kaynak" uyarısı
gösterebilir; **Yine de yükle** seçeneği kullanılabilir.

> `local.properties` içinde `sdk.dir=/Android/Sdk` satırı bulunmalıdır.
> Android Studio bunu otomatik oluşturur.
>
> Release imzalama şifreleri ve keystore dosyası depoya dahil **değildir**.
> Release derlemesi için `~/.gradle/gradle.properties` dosyasına
> `RELEASE_STORE_PASSWORD=...` ve `RELEASE_KEY_PASSWORD=...` satırlarını ekleyin
> (dosya izni `600` olmalıdır) ve keystore dosyasını `../release-keystore.jks`
> yoluna koyun.

### 3. Sunucu adresini girin

Uygulama ilk açılışta gömülü **Yerleşik liste (TeVe)** ile başlar; kendi
sunucunuzu kullanmak için sağ üstteki **dişli** simgesine dokunun ve adresi yazın:

- **Emülatörde:** `http://10.0.2.2:8000/channels.json`
- **Gerçek cihazda:** `http://<bilgisayarın-LAN-IP>:8000/channels.json`
  (sunucu betiği doğru adresi ekrana yazar)

Adres, doğrudan bir M3U çıktısı da olabilir. Örneğin:

```
http://10.0.2.2:8000/channels.m3u
```

## Sunucu sözleşmesi

### JSON biçimi (önerilen)

```json
{
  "version": 3,
  "updatedAt": "2026-11-18T09:00:00Z",
  "channels": [
    {
      "id": "trt1",
      "name": "TRT 1",
      "url": "https://ornek-sunucu/trt1/index.m3u8",
      "logo": "https://ornek-sunucu/logo/trt1.png",
      "group": "Ulusal"
    }
  ]
}
```

| Alan | Zorunlu | Açıklama |
| --- | --- | --- |
| `url` | **Evet** | Oynatılabilir yayın adresi (HLS/DASH/MP4) |
| `name` | Hayır | Kanal adı; yoksa "Kanal N" gösterilir |
| `id` | Hayır | Kararlı kimlik; yoksa adresten üretilir |
| `logo` | Hayır | Logo görseli adresi |
| `group` | Hayır | Grup / kategori adı |

Kabul edilen alternatif alan adları: `title`, `link`, `stream`, `streamUrl`,
`tvg-id`, `tvg-logo`, `icon`, `group-title`, `category`.

Kök nesne yerine doğrudan bir dizi de gönderilebilir:

```json
[ { "name": "Kanal 1", "url": "https://.../1.m3u8" } ]
```

`channels`, `items` veya `list` anahtarlarından biri de kabul edilir.

### M3U biçimi

```m3u
#EXTM3U
#EXTINF:-1 tvg-id="trt1" tvg-logo="https://.../trt1.png" group-title="Ulusal",TRT 1
https://ornek-sunucu/trt1/index.m3u8
```

`#EXTGRP:` satırları ve `group-title` özniteliği desteklenir. Xtream Codes
benzeri servislerin `get.php?...&type=m3u_plus` adresleri de doğrudan
sunucu adresi olarak girilebilir.

### Güncelleme davranışı

1. Uygulama açılır, önbellekteki liste hemen gösterilir.
2. "Açılışta otomatik güncelle" açıksa liste arka planda indirilir.
3. Başarılı indirme önbelleğe yazılır; araç çubuğunda son güncelleme saati görünür.
4. İndirme başarısızsa önbellekteki liste kullanılmaya devam eder.

Listeye kanal eklemek/çıkarmak için sadece sunucudaki dosyayı düzenleyin.
Uygulamada **Yenile** simgesine basmak yeterlidir.

## Kayıtlı listeler ve harici oynatıcı

### Yerleşik liste (TeVe)

- Uygulamanın içine gömülü **ailantus/TeVe** M3U listesi (44 kanal), yer imi menüsünde
  **Yerleşik liste (TeVe)** adıyla her zaman en üstte görünür.
- Silinemez; dokunulduğunda etkin liste olur ve hemen yüklenir.
- Liste APK içindeki kopyadan çevrimdışı da açılır; internet varsa güncel sürüm
  `raw.githubusercontent.com/ailantus/TeVe/refs/heads/main/data/playlist.m3u`
  adresinden indirilir.
- İlk kurulumda, eski örnek sunucu adresi (`10.0.2.2`) veya eski yerleşik liste
  adresi kullanılıyorsa etkin liste otomatik olarak yeni yerleşik listeye geçer.

### Listeleri kaydetme (en fazla 10)

1. Üst çubuktaki **yer imi** simgesine dokunun.
2. **Mevcut listeyi kaydet** düğmesiyle açık adresi adlandırarak kaydedin.
3. Kayıtlı bir listeye dokunmak adresi değiştirir ve listeyi hemen indirir.
4. Çöp kutusu simgesiyle liste silinir. Sınır **10 listedir**; dolduğunda uyarı gösterilir.

Kayıtlar cihazda `SharedPreferences` içinde tutulur; uygulama güncellense de silinmez.

### Doğrudan yayın adresi açma

Üst çubuktaki **⋮ → Yayın adresi aç** ekranına bir HLS/DASH/MP4 adresi yazın
(ör. `http://57.129.0.61:1990/stream/117/index.m3u8`) ve **Oynat** deyin. Adres
tek kanal olarak dahili oynatıcıda açılır; kanal listesini değiştirmeniz gerekmez.
Aynı adresi **Sunucu adresi** alanına girerseniz de tek kanallık liste olarak
yüklenir. Telegram veya tarayıcıda `.m3u8` bağlantısına dokunduğunuzda da
TvListesi oynatıcı olarak açılır (uygulama seçiminde **TvListesi**'ni seçin).
Telegram'dan açılan veya **Yayın adresi aç** ile girilen `.m3u8` bağlantısı hiç
değiştirilmeden, olduğu gibi kanal listesine eklenir. Sunucu adresi değişmez;
bağlantı doğrudan yayın linki olarak saklanır. Oynatıcıdaki kanal listesi paneli ve
liste düğmesi kaldırıldı; kanal değiştirme dokunma/kumanda hareketleriyle çalışır.

### Film listeleri

Uzantısı olmayan HLS film sayfaları (ör. `https://ha.vixolity.com/vs/tt36164852/`)
HLS olarak oynatılır; içerik adresinde `.m3u8` uzantısı bulunmasına gerek yoktur.
Bir film listesini **Sunucu adresi** alanına girip yenileyebilir veya
tarayıcı/Telegram'dan doğrudan uygulamayla açabilirsiniz. Listedeki her film ayrı
bir kanal olarak görünür; dokununca dahili oynatıcı açılır.

Temizlenmiş ve türe göre sınıflandırılmış film listesi (9.342 film, `Film Adı (Yıl)`
biçiminde adlar, IMDb tür/yıl bilgisi):

```
https://raw.githubusercontent.com/ailantus/TeVe/main/data/power-sinema.m3u
```

"Tüm gruplar" filtresinden tür seçerek filmleri türlerine göre gezebilirsiniz.

### Harici oynatıcı

- Oynatıcı ekranındaki **dışa aç** simgesi geçerli kanalı cihazdaki başka bir
  oynatıcıya (VLC, MX Player vb.) devreder.
- **Sunucu ayarları → Harici oynatıcıda aç** açıksa listeden kanala dokunmak
  doğrudan harici oynatıcıyı açar; uygun uygulama yoksa dahili oynatıcıya dönülür.

## Telefon → TV yansıtma

Ek cihaz veya DLNA sunucusu gerekmeden, aynı Wi-Fi ağındaki iki uygulama
doğrudan konuşur. TV kutusunda alıcı açılışta otomatik başlar; telefon
uygulaması UDP yayını ile alıcıyı bulur.

1. TV kutusunda uygulamayı açın. Gerekirse **⋮ → Yansıtma alıcısı** ekranından
   alıcının açık olduğunu ve IP adresini kontrol edin. Ekranda 6 haneli
   **eşleştirme kodu** gösterilir (her başlatmada değişir).
2. Telefonda oynatmak istediğiniz kanalı açın ve oynatıcıdaki **TV simgesine**
   dokunun.
3. Bulunan cihazlardan TV kutusunu seçin. İlk bağlantıda TV ekranındaki kodu
   girin; kod cihaz için kaydedilir, sonraki gönderimlerde tekrar sorulmaz.
4. Yayın TV'de açılır; telefondaki oynatıcı aynı sesi tekrar vermemek için
   duraklatılır. **Kumanda ekranı otomatik açılmaz.** TV simgesine **uzun
   basınca** açılan kumandadan TV'nin ses seviyesi artırılıp azaltılabilir,
   yayın 15 saniye ileri/geri sarılabilir ve oynatma duraklatılıp
   sürdürülebilir. **Başka cihaz** ile başka bir alıcı seçilebilir,
   **Bağlantıyı kes** TV'deki oynatmayı sonlandırır.
5. Bağlantı sürerken TV simgesine **dokunmak** seçili kanalı cihaz seçimi
   göstermeden kayıtlı TV'ye hemen gönderir. Kumandayı açmak için TV simgesine
   uzun basın. Telefonda **uygulama simgesine uzun basıp „Yansıtma kumandası“
   kısayolunu** seçerek de son kullanılan TV'ye doğrudan bağlanabilirsiniz;
   oynatıcı açılmaz, kumanda hemen görünür.

Ağda cihaz bulunamazsa **IP ile ekle** düğmesiyle TV'nin IP adresini elle
girebilirsiniz. Bu yöntem, yönlendiricinin UDP yayınını engellediği ağlarda
işe yarar. Yansıtma alıcısı yalnızca yerel ağda dinler ve yanlış kodla
gönderilen istekleri reddeder.

## DLNA / UPnP

Uygulama, ek bir kütüphane kullanmadan SSDP (multicast arama), UPnP cihaz tanımı,
SOAP **AVTransport** ve **ContentDirectory** protokollerini doğrudan konuşur.

### 1. Kanalları DLNA cihazına gönderme

1. Oynatıcı ekranındaki **DLNA'ya gönder** (yayın) simgesine dokunun.
2. Uygulama aynı ağdaki DLNA **oynatıcılarını** arar (3–4 saniye).
3. Bir cihaza dokunun: kanal adresi cihaza `SetAVTransportURI` + `Play` ile gönderilir.
   Çift ses olmaması için uygulamadaki oynatıcı duraklatılır.
4. Aynı iletişim kutusundaki **Durdur**, cihazda `Stop` gönderir.

### 2. DLNA sunucusundan içerik oynatma

1. Üst çubuktaki **⋮ → DLNA sunucuları** menüsünü açın.
2. Bulunan sunuculardan birini seçin; klasörler arasında gezinin.
3. Bir medya öğesine dokunun; uygulamanın oynatıcısında açılır (tek medya olduğu için kanal değiştirme devre dışıdır).

### 3. Cihazı adresle elle ekleme

SSDP araması bazı ağlarda (misafir Wi-Fi, VLAN, yalıtılmış ağlar) engellenebilir.
Her iki ekrandaki **Adresle ekle** düğmesiyle cihaz tanım adresini elle girin:

```
http://192.168.1.20:9197/dmr
```

Adres, cihazın UPnP tanım (description) XML'ini döndürmelidir. Adres `http://` ile
başlamıyorsa uygulama otomatik ekler.

### Sınırlamalar

- Cihaz ve telefon/TV **aynı yerel ağda** olmalıdır; pek çok yönlendirici istemci
  yalıtımını (AP isolation) varsayılan olarak açar, bu DLNA'yı engeller.
- Bazı DLNA oynatıcıları HLS (`.m3u8`) oynatamaz. Uygulama yayını olduğu gibi
  gönderir; cihaz desteklemiyorsa oynatmaz.
- DLNA oynatıcıya gönderilen yayın **cihaz üzerinden** açılır; uygulama kapanabilir.
- Android TV'lerde Ethernet kullanılıyorsa multicast kilidi Wi-Fi servisine bağlıdır;
  cihaz yine de bulunamazsa adresle ekleme yolunu kullanın.

## Oynatıcıda kaydırma hareketleri

İzleme ekranında dokunarak kaydırma yaparak kontroller kullanılabilir; ekrandaki
**önceki/sonraki düğmeleri kaldırıldı**, kanal değişimi kaydırmayla yapılır:

- **Sola veya sağa kaydırmak:** sıradaki / önceki kanala geçer (liste başa/sona
  gelince sarmalanır). Kısa ve hızlı bir kaydırma (fling) yeterlidir.
- **Yukarı veya aşağı kaydırmak:**
  - ekranın **sol yarısında** başlatırsanız **parlaklığı**,
  - ekranın **sağ yarısında** başlatırsanız **ses seviyesini** ayarlar.
  - Ekran ortasında yüzde ve ilerleme çubuğuyla kısa bir gösterge görünür.

Hareketler yatay/dikey ekranda çalışır; hata ekranı veya açık kanal paneli
üzerinde devre dışıdır. Parlaklık oynatıcıdan çıkılınca eski değerine döner.
Kumanda ile **CH+ / CH−** çalışmaya devam eder; **D-pad sağ/sol** ise 15 saniye
ileri/geri sarar (kanal paneli açıkken veya bir düğme odaktayken gezinmeyi bozmaz).

Yayın ileri/geri sarılabiliyorsa (örneğin VOD veya zaman kaydırmalı HLS)
alt çubukta **zaman çizgisi** ve `geçerli / toplam` süre görünür. Çizgideki
topu sürükleyerek istediğin ana geçebilirsiniz; alt çubuktaki **−15 / +15**
düğmeleri, kumandadaki **D-pad sağ/sol** ve **sar (rewind / fast forward)**
tuşları 15 saniyelik adımlarla aynı çizgiyi kaydırır. Atlama düğmeleri her
yayında görünür; sarma desteklenmeyen yayınlarda bastığınızda uyarı gösterilir. Zaman çizgisi yalnızca
ileri/geri sarılabilen yayınlarda görünür.

## Telegram kanalından liste bulma

**⋮ → Telegram'dan liste bul** ekranında adres kutusu görünür ve varsayılan olarak
`https://t.me/s/TvListesi` gelir; **Ara**'ya bastığınızda bu önizleme taranır.
Uygulama kanalın tüm mesajlarını tarar; mesajlardaki `.m3u`/`.m3u8` adreslerini,
`.m3u`/`.m3u8` uzantılı **dosya eklerini**, tek kanal yayınlarını, `/dizi` ya da
`/film` ile biten bağlantıları ve
`...?ID=...&type=Film` / `type=Dizi` biçimindeki PHP liste adreslerini listeler.
Bulunan kaynakların tamamı arama biter bitmez
otomatik olarak kontrol edilir; **Tümünü kontrol et** düğmesiyle yeniden
sınanabilir. Dosya eklerinin önizlemede yalnızca adı göründüğü için liste içeriği
herkese açık medya proxy'si üzerinden (`tg.i-c-a.su/media/<kanal>/<mesaj-no>`)
indirilir. **Kullan** ile kaynağı yükleyebilirsiniz. Tek kanal yayınları
mevcut listenin yerine geçer: önceki kanallar silinir, adres değiştirilmeden
yalnızca `Kanal 1` eklenir. M3U listeleri etkin liste olarak yüklenir.

Güncelleme kontrolü bu ekrandan ayrıdır: **⋮ → Güncellemeyi kontrol et** GitHub
Releases API'sine (`api.github.com/repos/ailantus/TvListesi/releases/latest`)
bakıp son sürümün `.apk` varlığını indirir. Telegram kanalı artık güncelleme
için taranmaz. Uygulama açılırken de bu kontrol otomatik yapılır; dosya önce
uygulama içinde indirilir ve APK'nın kendi sürüm bilgisi okunur. Sürüm kurulu
sürümle aynıysa ya da daha eskiyse dosya silinir ve **hiçbir güncelleme
bildirimi gösterilmez**. Yeni sürümse **Yeni sürüm var** uyarısı çıkar;
**Güncelle** düğmesi indirilen dosyayı doğrudan kurulum ekranına gönderir. Aynı
sürüm için uyarı bir kez gösterilir; **Sonra** seçilirse otomatik kontrol tekrar
bildirim çıkarmaz.

Yeni sürüm yayınlamak için `ailantus/TvListesi` deposunda bir release oluşturun
(etiket `v2.0.31` gibi `versionName` ile eşleşmelidir) ve imzalı APK'yı varlık
olarak ekleyin. Uygulama aynı etiketli sürümü tekrar indirmez; indirmeden önce
etiketi kurulu sürümle karşılaştırır, gerekiyorsa APK'nın `versionCode`/
`versionName` değerini okuyup imzasını doğrular. Telegram kanalından bulunan doğrudan `.apk` veya Google Drive
bağlantıları da indirilebilir; Telegram dosya eklerinin `t.me/s` önizlemesinde
indirme adresi bulunmadığından ek olarak paylaşılan APK'lar indirilemez.
Android 8+ için **Bilinmeyen kaynaklardan yükleme** izni kurulumdan önce istenir.
İndirilen APK'nın imzası kurulu uygulamayla karşılaştırılır; uyuşmazsa kurulum
yapılmaz.

## Yayın linklerini kontrol etme

**⋮ → Yayın linklerini kontrol et** ekranı etkin listedeki her kanal için küçük
bir `Range` isteği yapar (canlı yayını baştan sona indirmez). Sonuçlar
**Tümü / Çalışanlar / Hatalılar** olarak filtrelenir. **Hatalıları kaldır**
düğmesi çalışan kanalları cihazınıza `local://filtrelenmis` adresli yeni bir
M3U listesi olarak kaydeder ve etkin liste yapar; kayıtlı listeler menüsünden
isterseniz geri dönebilirsiniz.

> Not: RTSP/RTMP adresleri HTTP olmadığından bu kontrol yöntemiyle sınanamaz ve
> "HTTP olmayan adres" olarak işaretlenir. Bu, yayının bozuk olduğu anlamına
> gelmez; yalnızca dahili oynatıcıda test edilmelidir.

## Proje yapısı

```
TvListesi/
├─ app/src/main/java/com/example/tvlistesi/
│  ├─ MainActivity.kt          # liste, arama, grup filtresi, ayarlar
│  ├─ PlayerActivity.kt        # Media3 ExoPlayer oynatıcı + DLNA gönderme
│  ├─ LinkCheckActivity.kt     # Toplu yayın adresi kontrolü
│  ├─ DlnaBrowserActivity.kt   # DLNA sunucularında gezinme
│  ├─ data/Channel.kt          # kanal modeli
│  ├─ data/SavedList.kt        # kayıtlı liste modeli
│  ├─ data/ExternalPlayer.kt   # harici oynatıcıya devretme
│  ├─ data/DlnaDevice.kt       # DLNA cihaz modeli
│  ├─ data/DlnaEntry.kt        # DLNA klasör/medya modeli
│  ├─ data/PlaylistParser.kt   # JSON + M3U ayrıştırıcı
│  ├─ data/PlaylistRepository.kt # indirme, önbellek, ayarlar, yerel liste
│  ├─ data/LinkChecker.kt      # tek yayın adresi erişilebilirlik testi
│  ├─ dlna/DlnaXml.kt          # UPnP/SOAP/DIDL ayrıştırma ve üretme
│  ├─ dlna/DlnaClient.kt       # HTTP + SOAP istemcisi
│  ├─ dlna/Ssdp.kt             # SSDP cihaz araması
│  ├─ dlna/DlnaDiscovery.kt    # önbellekli cihaz keşfi
│  └─ ui/                      # adaptörler, logo yükleyici, kontrol adaptörleri
├─ app/src/main/res/           # XML düzenleri, tema, simgeler
├─ sunucu/
│  ├─ channels.json            # örnek JSON liste
│  ├─ channels.m3u             # örnek M3U liste
│  └─ baslat.py                # örnek HTTP sunucusu
└─ README.md
```

## Android TV

- `LEANBACK_LAUNCHER` giriş noktası ve 320×180 TV banner'ı tanımlıdır.
- Liste öğeleri D-pad ile odaklanabilir; odaklanan kartın kenarlığı vurgulanır.
- Oynatıcıda kumanda ile önceki/sonraki kanal ve oynat/duraklat kullanılabilir; dokunmatik ekranda kanal değişimi sağa/sola kaydırmayla yapılır (bkz. "Oynatıcıda kaydırma hareketleri").
- Oynatıcıda üst/alt çubuklar, sağdaki kanal paneli ve durum/gezinme çubukları **3 saniye** sonra kendiliğinden gizlenir; videoya dokunmak veya herhangi bir kumanda tuşuna basmak hepsini yeniden gösterir.
- Gizleme sayacı yalnızca gerçek dokunma/tuş ile sıfırlanır; pencere odağı gibi sistem olayları gizlemeyi süresiz ertelemez. Arayüz gizliyken ilk dokunuş yalnızca arayüzü gösterir.
- Kanal listesi (giriş) ekranındaki üst çubuk, arama alanı ve grup filtresi **her zaman görünür**; bu ekranda otomatik gizleme yoktur.
- **Harici oynatıcı** (VLC / MX Player) açıksa gizleme uygulanmaz; çünkü ekranda uygulamanın değil harici oynatıcının arayüzü görünür. Bu durumda Ayarlar'dan "Harici oynatıcıda aç" seçeneğini kapatın.
- Uygulama sürümü **Ayarlar → Sunucu** iletişim kutusunun altında yazar (ör. `Sürüm: 1.9`).
- İlk kez tam ekrana geçerken Android'in "Tam ekran" onayı bir kez çıkabilir; **Anladım** seçildikten sonra tekrar görünmez.
- Sağdaki kanal panelinde D-pad ile gezinilir; **OK** tuşu seçili kanalı açar.
- Kanal tuşları: **CH+ / CH−** (bazı kumandalarda `Page Up` / `Page Down` veya medya ileri/geri) kanalı değiştirir; liste başa/sona gelince sarmalanır.
- **Oynat/Duraklat** tuşu da çalışır.

Kurulum:

```bash
adb connect <TV-IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Sorun giderme

| Belirti | Çözüm |
| --- | --- |
| "Kanal listesi yüklenemedi" | Adresi ve Wi-Fi bağlantısını kontrol edin; sunucu betiğinin çalıştığından emin olun. |
| Emülatörde bağlanmıyor | Bilgisayar adresi olarak `10.0.2.2` kullanın, `localhost` değil. |
| "Liste boş veya biçimi tanınmadı" | Dosya `#EXTM3U` ile başlamalı veya geçerli JSON olmalı; en az bir `url` içermeli. HLS master playlist'leri (`#EXT-X-STREAM-INF`) de artık tek kanal olarak kabul edilir. |
| HTTP adresi reddediliyor | Uygulama `usesCleartextTraffic` açık olduğu için `http://` kabul eder; yine de adresin `http://` ile başladığından emin olun. |
| Yayın açılmıyor | Yayın adresi cihazdan erişilebilir olmalı. Tarayıcıda test edin; HLS adresi `.m3u8` içermiyorsa MIME türü algılanamayabilir. |
| Logo görünmüyor | Logo adresi HTTPS olmalı ve erişilebilir olmalı; aksi halde TV simgesi gösterilir. |
| DLNA cihazı bulunamıyor | Aynı Wi-Fi ağında olun; yönlendiricide istemci yalıtımını kapatın. Gerekirse **Adresle ekle** ile cihaz tanım adresini girin. |
| DLNA'ya gönderilen yayın açılmıyor | Cihaz HLS/DASH desteklemiyor olabilir. MP4 tabanlı bir yayınla deneyin. Hata mesajında cihazın döndürdüğü SOAP açıklaması da gösterilir; alıcı Android 12+ ise görüntü için cihazdaki bildirimden **Oynatıcıyı aç** düğmesine basın. |

## Yasal uyarı

Bu uygulama yalnızca bir oynatıcı ve liste istemcisidir. Yayınladığınız veya
izlediğiniz içeriklerin telif ve yayın haklarına sahip olduğunuzdan ya da yasal
izin kapsamında olduğundan emin olun. Örnek listedeki adresler yalnızca teknik
test amaçlı herkese açık demo yayınlarıdır.
