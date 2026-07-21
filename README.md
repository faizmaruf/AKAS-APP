# Windu Kopi by Akas

Aplikasi Android hybrid Windu Kopi berbasis Akas. UI dan transaksi dibuka melalui WebView, sedangkan scanner dan printer ESC/POS dijalankan secara native.

## Konfigurasi aplikasi

Konfigurasi build berada di `app/src/main/assets/pos-config.txt`:

```text
START_URL=https://windu-kopi.akas.my.id/
ALLOWED_HOSTS=akas.my.id,*.akas.my.id
APP_NAME=Windu Kopi by Akas
LAUNCHER_LOGO=drawable/windu_kopi_logo.png
DEFAULT_PAPER_WIDTH_MM=58
```

- `START_URL`: halaman pertama WebView.
- `ALLOWED_HOSTS`: domain yang boleh tetap dibuka di WebView, dipisahkan koma. Gunakan `*.domain.com` untuk subdomain.
- `LAUNCHER_LOGO`: asset logo launcher yang dibaca oleh build. Konfigurasi Windu Kopi menggunakan `app/src/main/res/drawable/windu_kopi_logo.png`.
- `DEFAULT_PAPER_WIDTH_MM`: `58` atau `80`. Pengguna dapat menggantinya dari halaman konfigurasi printer.

Untuk build tenant tertentu, ubah hanya URL awal dan biarkan wildcard host tetap aktif. Contoh:

```text
START_URL=https://windu-kopi.akas.my.id/login
ALLOWED_HOSTS=akas.my.id,*.akas.my.id
```

Perubahan file konfigurasi atau logo memerlukan rebuild APK karena keduanya dibundel saat build.

## Menjalankan project

1. Buka folder ini melalui Android Studio.
2. Tunggu Gradle Sync selesai.
3. Sambungkan perangkat Android lalu tekan **Run**.
4. Pairing printer 58 mm terlebih dahulu melalui pengaturan Bluetooth Android.

URL awal berada di `app/build.gradle.kts` pada `BuildConfig.POS_URL`. Ganti URL tersebut jika POS menggunakan path atau subdomain tersendiri.

## JavaScript bridge

Bridge hanya tersedia di aplikasi Android dengan nama `window.AkasPOS`.

Untuk kompatibilitas dengan bundle web yang masih lama, APK juga menyediakan alias
`window.AkasPOS` dan event `akas:native-print`. Alias ini merupakan kontrak teknis
sementara, bukan branding aplikasi.

```javascript
const isAkasAndroid = window.AkasPOS?.getPlatform?.() === "android";

// Membuka QR/barcode scanner native.
window.AkasPOS.scanBarcode();

// Membuka dialog printer Bluetooth yang sudah di-pairing.
window.AkasPOS.selectPrinter();

// Mengirim JSON invoice. Kotlin akan memformatnya untuk kertas 58 mm.
window.AkasPOS.printReceipt(JSON.stringify(receipt));
```

Callback opsional yang dapat dipasang oleh website:

```javascript
window.onBarcodeScanned = (value) => {
  console.log("Hasil scan:", value);
};

window.onBarcodeScanCancelled = () => {};
window.onPrinterSelected = (address) => {};
window.onPrintSuccess = () => {};
window.onPrinterError = (message) => {
  alert(message);
};
```

## Integrasi tombol Print native dengan React

Saat tombol **Print Struk** native ditekan, build Windu Kopi mengirim event kompatibilitas `akas:native-print`, sesuai bundle web tenant yang aktif. React mendengarkan event ini, mengambil invoice dari API, lalu mengirim JSON hasilnya ke Kotlin:

```javascript
useEffect(() => {
  const handleNativePrint = async (event) => {
    try {
      const invoiceId = event.detail.invoiceId;
      const response = await api.get(`/admin/sales/invoice/${invoiceId}`);
      const invoice = response.data;

      const receipt = {
        storeName: "WINDU KOPI",
        address: invoice.store_address,
        invoiceNumber: invoice.invoice_number,
        invoiceId,
        date: invoice.created_at,
        cashier: invoice.cashier_name,
        items: invoice.items.map((item) => ({
          name: item.product_name,
          qty: Number(item.quantity),
          price: Number(item.price),
          subtotal: Number(item.subtotal),
        })),
        subtotal: Number(invoice.subtotal),
        discount: Number(invoice.discount ?? 0),
        tax: Number(invoice.tax ?? 0),
        total: Number(invoice.total),
        paymentMethod: invoice.payment_method,
        paid: Number(invoice.paid),
        change: Number(invoice.change),
        footer: "Terima kasih",
      };

      const nativeBridge = window.AkasPOS ?? window.AkasPOS;
      nativeBridge.printReceipt(JSON.stringify(receipt));
    } catch (error) {
      const nativeBridge = window.AkasPOS ?? window.AkasPOS;
      nativeBridge?.onPrintDataError?.(String(error));
    }
  };

  window.addEventListener("akas:native-print", handleNativePrint);
  return () => window.removeEventListener("akas:native-print", handleNativePrint);
}, []);
```

Sesuaikan URL API dan nama field dengan response backend yang sebenarnya. `receiptText` juga dapat dikirim sebagai alternatif jika website ingin menyusun teks struk sendiri.

## Login dan password

Cookie/session WebView disimpan dan di-flush saat aplikasi masuk background, sehingga menekan Home lalu membuka aplikasi lagi tidak menghapus login. WebView juga mengaktifkan Android Autofill. Agar Google Password Manager menawarkan penyimpanan akun, form web sebaiknya memakai atribut berikut:

```html
<input name="username" autocomplete="username" />
<input type="password" name="password" autocomplete="current-password" />
```

Password tidak disimpan mentah oleh aplikasi Windu Kopi by Akas.

## Cakupan versi pertama

- WebView dengan JavaScript, cookie/session, DOM storage, loading, dan retry.
- Navigasi dibatasi ke `akas.my.id` dan seluruh subdomain tenant seperti `windu-kopi.akas.my.id`; tautan lain dibuka di aplikasi luar.
- QR/barcode scanner memakai kamera Android.
- Pemilihan paired printer Bluetooth Classic.
- Print JSON invoice menggunakan protokol ESC/POS melalui Bluetooth SPP.
- Format dinamis 32 kolom untuk kertas 58 mm dan 48 kolom untuk kertas 80 mm.
- Struk profesional dengan logo bitmap, hierarki font ESC/POS, total tebal, dan QR website.
- Isi QR otomatis mengikuti `START_URL` dari `pos-config.txt`.
- Callback sukses/gagal ke website.

Printer USB dan LAN belum diimplementasikan. Beberapa printer murah memiliki variasi perintah ESC/POS; fungsi potong kertas akan diabaikan oleh printer 58 mm yang tidak memiliki cutter.
