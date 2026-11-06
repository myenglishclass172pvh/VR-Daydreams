# Daydream BLE — ứng dụng Android đọc tay cầm Google Daydream View

Ứng dụng quét BLE, kết nối tay cầm Daydream, giải mã gói 20 byte và hiển thị nút bấm,
touchpad, IMU cùng log raw hex theo thời gian thực.

## 1. Tạo project trong Android Studio

- **New Project → Empty Views Activity** (quan trọng: *Views*, không phải *Compose*).
- Language: **Kotlin**
- Package name: `com.example.daydreamble` — nếu bạn đổi tên gói, nhớ sửa dòng `package`
  đầu hai file `.kt`.
- Minimum SDK: **API 21** trở lên (BLE cần API 18, scanner mới cần API 21).

## 2. Chép file vào project

| File trong gói này   | Đặt vào                                                |
|----------------------|--------------------------------------------------------|
| `MainActivity.kt`    | `app/src/main/java/com/example/daydreamble/`           |
| `DaydreamBle.kt`     | `app/src/main/java/com/example/daydreamble/`           |
| `activity_main.xml`  | `app/src/main/res/layout/` (ghi đè file sẵn có)        |
| `AndroidManifest.xml`| `app/src/main/` (ghi đè)                               |

## 3. `app/build.gradle.kts`

Project mẫu đã có sẵn các mục này; chỉ cần kiểm tra lại:

```kotlin
android {
    namespace = "com.example.daydreamble"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.daydreamble"
        minSdk = 21
        targetSdk = 34
    }
    buildFeatures { viewBinding = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
```

Không cần thư viện BLE bên thứ ba — toàn bộ dùng API `android.bluetooth` gốc.

## 4. Chạy thử

1. Cắm điện thoại, bấm **Run**. BLE không hoạt động trên emulator, phải chạy máy thật.
2. **Gỡ ghép nối (unpair) tay cầm trong Cài đặt → Bluetooth nếu nó đang ở đó.**
   Khi tay cầm đã bond với hệ thống, dịch vụ VR của Google có thể giữ kết nối GATT và
   app của bạn sẽ không nhận được notify.
3. Giữ **nút Home** (nút tròn phía dưới) khoảng 3 giây tới khi LED nhấp nháy — tay cầm
   vào chế độ quảng bá BLE.
4. Bấm **Quét thiết bị**. Mục có dấu ★ với tên `Daydream controller` chính là tay cầm.
   Chạm vào nó để kết nối.
5. Sau vài giây log sẽ báo "Notify đã bật" và panel giữa bắt đầu chạy số.

## 5. Giao thức BLE của tay cầm

| Thành phần | UUID |
|---|---|
| Service dữ liệu | `0000fe55-0000-1000-8000-00805f9b34fb` |
| Characteristic notify | `00000001-1000-1000-8000-00805f9b34fb` |
| CCCD (bật notify) | `00002902-0000-1000-8000-00805f9b34fb` |

Tay cầm đẩy gói **20 byte, khoảng 60 lần/giây**. Dữ liệu là một dòng bit liên tục,
**không căn theo byte** — đây là lý do bộ giải mã phải ghép bit thủ công:

| Bit | Trường | Độ rộng |
|---|---|---|
| 0–8 | timestamp | 9 |
| 9–13 | sequence | 5 |
| 14–26 / 27–39 / 40–52 | orientation x / y / z | 13 bù 2 |
| 53–65 / 66–78 / 79–91 | accelerometer x / y / z | 13 bù 2 |
| 92–104 / 105–117 / 118–130 | gyroscope x / y / z | 13 bù 2 |
| 131–138 | touchpad x | 8 (0–255) |
| 139–146 | touchpad y | 8 (0–255) |
| 147–151 | 5 nút bấm | 1 bit mỗi nút |

Hệ số quy đổi: orientation `×2π/4095` (rad), accelerometer `×8·9.8/4095` (m/s²),
gyroscope `×(2048/180)·π/4095` (rad/s).

Touchpad trả `(0,0)` khi không có ngón tay chạm, nên app dùng điều kiện đó để suy ra
trạng thái "đang chạm".

## 6. Nếu nút bấm bị nhận sai

Việc gán bit cho từng nút đến từ reverse-engineering cộng đồng và có vài bản mâu thuẫn
nhau. App đã in sẵn **raw hex** ra log mỗi lần trạng thái nút đổi. Cách hiệu chuẩn:

1. Nhấn giữ từng nút một, xem byte thứ 19 (chỉ số 18) trong log đổi thế nào.
2. Mở `DaydreamBle.kt`, sửa các mask trong khối:

```kotlin
val clickBtn = (u[18] and 0x01) != 0
val homeBtn  = (u[18] and 0x02) != 0
val appBtn   = (u[18] and 0x04) != 0
val volDown  = (u[18] and 0x08) != 0
val volUp    = (u[18] and 0x10) != 0
```

Lưu ý nút **Home** trên firmware gốc đồng thời là nút nguồn: giữ lâu sẽ khiến tay cầm
tự ngắt kết nối.

## 7. Các lỗi hay gặp

| Hiện tượng | Nguyên nhân thường gặp |
|---|---|
| Quét không ra gì trên Android 10/11 | Chưa bật Dịch vụ vị trí (bắt buộc cho BLE scan ở API ≤ 30) |
| `onScanFailed` mã 2 | Bluetooth vừa bị bật/tắt liên tục; tắt bật lại Bluetooth |
| Kết nối rồi rớt ngay, `status=133` | Tay cầm đang bond với hệ thống → unpair, hoặc thử `connectGatt` lại sau 1–2 giây |
| Kết nối được nhưng không thấy service `FE55` | Đã kết nối nhầm thiết bị, hoặc đang kết nối qua BR/EDR thay vì LE |
| Không có gói nào về | CCCD chưa ghi thành công — xem log "Bật notify thất bại" |

## 8. Ghi chú

Dịch vụ Daydream đã bị Google ngừng hỗ trợ, nhưng phần cứng tay cầm vẫn quảng bá BLE
bình thường và hoạt động độc lập với ứng dụng Daydream. Mã trong gói này chỉ *đọc*
dữ liệu; nếu muốn dùng tay cầm như thiết bị điều khiển chung cho ứng dụng khác, bạn có
thể chuyển phần `DaydreamBleClient` sang một `Service` chạy nền (kèm foreground
notification) và phát `DaydreamState` qua `SharedFlow`.
