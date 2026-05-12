# Developer Quickstart — Personal Phone Agent (PPA)

> Dành cho developer mới tham gia hoặc cần dựng lại project từ đầu.

## 1. Môi trường phát triển

### 1.1 Yêu cầu

| Công cụ | Phiên bản tối thiểu | Ghi chú |
|---|---|---|
| Android Studio | Hedgehog (2023.1+) hoặc mới hơn | Bắt buộc — dùng để build & debug |
| JDK | 17 | Đi kèm Android Studio |
| Kotlin | 1.9.22 | Gradle tự tải |
| AGP | 8.2.2 | Android Gradle Plugin |
| Thiết bị test | Android 10+ (API 29) | Emulator hoặc thiết bị thật |

### 1.2 Kiểm tra môi trường

```bash
# Xác nhận JDK
java -version                # Phải là 17+

# Xác nhận Android SDK
echo $ANDROID_HOME           # Phải trỏ đến SDK (vd: ~/Android/Sdk)

# Kiểm tra SDK platforms đã cài
ls $ANDROID_HOME/platforms/  # Phải có android-34
```

---

## 2. Mở và build project

### 2.1 Clone / mở project

1. Mở Android Studio
2. **File → Open** → chọn thư mục `personal-agent/`
3. Chờ Gradle sync (vài phút lần đầu, tải dependencies)

### 2.2 Build

```bash
# Build từ terminal (không cần Android Studio)
cd personal-agent
./gradlew assembleDebug
```

APK sẽ ở: `app/build/outputs/apk/debug/app-debug.apk`

### 2.3 Build trên Android Studio

- **Build → Make Project** (Ctrl+F9)
- **Run → Run 'app'** (Shift+F10) — build + cài lên thiết bị

---

## 3. Chạy trên thiết bị

### 3.1 Thiết bị thật (khuyến nghị)

1. Bật **Developer Options** + **USB Debugging** trên phone
2. Cắm USB, chấp nhận fingerprint
3. Chọn thiết bị trong dropdown (gần nút Run)
4. Nhấn **Run**

> ⚠️ Cần thiết bị thật để test ContentObserver (MediaStore hoạt động với camera thật). Emulator có thể không trigger observer đúng.

### 3.2 Emulator

1. Tạo AVD với Android 13+ (API 33+), có Play Store
2. Khởi động emulator
3. Chụp ảnh bằng camera giả lập (có thể trigger qua MediaStore)
4. Nhấn **Run**

> ⚠️ `PhotoTriggerObserver` cần camera thực sự lưu ảnh vào MediaStore. Trên emulator, bạn có thể dùng `adb push` để copy ảnh vào `/sdcard/DCIM/Camera/` và trigger MediaStore scan thủ công.

---

## 4. Cấu trúc project chi tiết

```
personal-agent/
├── app/
│   ├── build.gradle.kts         # AGP 8.2, compileSdk 34, minSdk 29
│   └── src/main/
│       ├── AndroidManifest.xml  # Permissions, service, activity
│       └── java/com/personalagent/
│           │
│           ├── MainActivity.kt          # Dashboard UI
│           ├── WorkerApp.kt             # Application (DB + Timber init)
│           ├── WorkerForegroundService.kt # Foreground service
│           │
│           ├── agent/                   # Core agent logic
│           │   ├── PersonalTask.kt      # Room Entity
│           │   ├── PersonalTaskDao.kt   # Room DAO
│           │   ├── TaskExecutor.kt      # Task → Runner dispatcher
│           │   ├── TaskPollingLoop.kt   # Main execution loop
│           │   ├── ImageRunner.kt       # Image resize pipeline
│           │   ├── PhotoTriggerObserver.kt # MediaStore watcher
│           │   ├── AiTestHelper.kt      # Dev test utility (Phase 2)
│           │   └── model/               # AI ModelPool (Phase 2)
│           │       ├── ModelMeta.kt     # Model metadata
│           │       ├── ModelRegistry.kt # 7-model registry
│           │       ├── ModelCache.kt    # LRU cache + health gate
│           │       ├── ModelLoader.kt   # TFLite/ONNX loader (stub)
│           │       ├── AIRunner.kt      # AI inference dispatcher
│           │       └── ModelPreloader.kt # Predictive pre-warm
│           │
│           ├── config/                  # Cấu hình & database
│           │   ├── AppDatabase.kt       # Room singleton
│           │   └── WorkerConfig.kt      # SharedPreferences config
│           │
│           ├── health/                  # Device health monitoring
│           │   └── HealthCollector.kt   # Battery, thermal, RAM, network
│           │
│           ├── identity/                # Device static info
│           │   └── DeviceInfoCollector.kt
│           │
│           └── lifecycle/               # Agent lifecycle
│               └── ServiceLifecycle.kt  # Start/stop loops & observers
│
├── gradle/
│   └── libs.versions.toml              # Version catalog
├── build.gradle.kts                    # Root build file
├── settings.gradle.kts
└── docs/
    ├── phase-1-guide.md                # Phase 1 implementation guide
    ├── dev-quickstart.md               # ← Tài liệu này
    └── models.md                       # AI model selection strategy
```

### 4.1 Dependency graph giữa các module

```text
WorkerApp (Application)
  └── AppDatabase.getInstance()
         └── PersonalTaskDao

WorkerForegroundService
  └── ServiceLifecycle
         ├── WorkerConfig.load()
         ├── DeviceInfoCollector.collect()
         ├── HealthCollector.snapshot()
         ├── PhotoTriggerObserver → PersonalTaskDao.insert()
         └── taskPollingLoop()
                ├── PersonalTaskDao.getOldestPending()
                ├── PersonalTaskDao.updateStatus()
                ├── TaskExecutor.execute()
                │     └── ImageRunner.execute()
                ├── PersonalTaskDao.completeTask()
                └── PersonalTaskDao.updatePayload()

MainActivity (Dashboard)
  └── PersonalTaskDao.getPendingTasksFlow()
  └── PersonalTaskDao.getRecentTasksFlow()
  └── WorkerForegroundService (start/stop via Intent)
```

---

## 5. Thêm task type / runner mới

### 5.1 Định nghĩa task type mới

**Bước 1 — Tạo runner class:**

```kotlin
// agent/TextRunner.kt
package com.personalagent.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * Processes TEXT-type tasks: summarise or classify text content.
 */
class TextRunner(
    private val context: Context,
    private val task: PersonalTask,
) {
    var outputPath: String? = null
        private set

    suspend fun execute(): Boolean = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject(task.payloadJson)
            val text = payload.optString("text", "")
            val operation = payload.optString("operation", "summarise")

            Timber.i("TextRunner: operation=%s textLen=%d", operation, text.length)

            // TODO: Tích hợp model AI (Phase 2)

            true
        } catch (e: Exception) {
            Timber.e(e, "TextRunner: failed")
            false
        }
    }
}
```

**Bước 2 — Đăng ký trong TaskExecutor:**

```kotlin
// agent/TaskExecutor.kt — thêm case mới trong when
"TEXT" -> {
    val runner = TextRunner(context, task)
    val ok = runner.execute()
    ExecutionResult(success = ok)
}
```

**Bước 3 — (Optional) Thêm trigger để tạo task:**

```kotlin
// Ví dụ: SMS trigger (Phase 2)
class SmsTriggerObserver(...) : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        // Parse SMS → PersonalTask(type="TEXT", payloadJson=...)
        // dao.insert(task)
    }
}
```

### 5.2 Payload schema cho task type mới

Mỗi task type có schema `payloadJson` riêng, quyết định bởi runner tương ứng. Không có schema validation ở DB level — runner tự parse và xử lý lỗi.

Ví dụ schema cho TEXT:
```json
{
  "text": "Nội dung cần xử lý",
  "operation": "summarise",       // hoặc "classify", "extract"
  "language": "vi",
  "maxLength": 200
}
```

---

## 6. Debug & Logging

### 6.1 Timber logging

Mọi log dùng Timber (wrapper của Android Log). Tag tự động là class name.

```kotlin
Timber.d("Debug message với format %s", value)
Timber.i("Info message")
Timber.w("Warning")
Timber.e(exception, "Error với exception")
```

### 6.2 Xem log

```bash
# Tất cả log từ app
adb logcat -s PersonalAgent:*

# Chỉ log từ package
adb logcat | grep "com.personalagent"

# Filter theo tag cụ thể
adb logcat | grep "TaskPolling\|ImageRunner\|PhotoTrigger"
```

### 6.3 Inspect database

```bash
# Pull database từ thiết bị
adb shell run-as com.personalagent cat /data/data/com.personalagent/databases/personal_agent.db > /tmp/ppa.db

# Mở bằng sqlite3
sqlite3 /tmp/ppa.db
```

```sql
-- Xem tất cả task
SELECT id, type, status, priority, created_at FROM personal_tasks;

-- Xem payload của task cụ thể
SELECT payload_json FROM personal_tasks WHERE id = '...';

-- Đếm theo trạng thái
SELECT status, COUNT(*) FROM personal_tasks GROUP BY status;

-- Xóa tất cả task (reset)
DELETE FROM personal_tasks;
```

### 6.4 Test ContentObserver thủ công

```bash
# Push ảnh vào DCIM và trigger MediaStore scan
adb push test.jpg /sdcard/DCIM/Camera/test.jpg
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
  -d file:///sdcard/DCIM/Camera/test.jpg

# Hoặc dùng content insert (API 29+)
adb shell content insert \
  --uri content://media/external/images/media \
  --bind _data:s:/sdcard/DCIM/Camera/test.jpg \
  --bind mime_type:s:image/jpeg
```

---

## 7. CI / Build nhanh

```bash
# Build + unit test (khi có)
./gradlew test

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Lint check
./gradlew lint

# Clean build
./gradlew clean assembleDebug
```

---

## 8. Troubleshooting

### 8.1 Gradle sync fail

| Lỗi | Nguyên nhân | Cách sửa |
|---|---|---|
| `KSP plugin not found` | Thiếu KSP trong root build.gradle.kts | Thêm `alias(libs.plugins.ksp) apply false` |
| `Room compiler error` | Phiên bản Room không khớp KSP | Đảm bảo room 2.6.1 + ksp 1.9.22-1.0.17 |
| `Unresolved reference: timber` | Thiếu dependency | Sync lại Gradle |

### 8.2 Build fail

| Lỗi | Nguyên nhân | Cách sửa |
|---|---|---|
| `android:foregroundServiceType` | compileSdk quá thấp | compileSdk ≥ 34 |
| `minSdk too low` | API không hỗ trợ | minSdk = 29 |

### 8.3 Runtime issues

| Lỗi | Nguyên nhân | Cách sửa |
|---|---|---|
| Worker không start | Permission foreground service | Cấp quyền thủ công trong Settings |
| Không thấy task mới | ContentObserver không trigger | Kiểm tra permission READ_MEDIA_IMAGES |
| Dashboard không cập nhật | Flow collect bị cancel | Kiểm tra `onPause`/`onResume` lifecycle |
| ImageRunner crash | Ảnh quá lớn, OOM | Giảm MAX_DIMENSION hoặc thêm downsampling |

### 8.4 Log xuất hiện nhiều "no tasks available"

Điều này là bình thường — polling loop đang chờ task mới. Nếu không thấy task nào sau khi chụp ảnh:
1. Kiểm tra ContentObserver đã register chưa: tìm log "PhotoTriggerObserver registered"
2. Kiểm tra quyền READ_MEDIA_IMAGES đã cấp
3. Kiểm tra DB: `SELECT * FROM personal_tasks;` — nếu có task nghĩa là observer hoạt động

---

## 9. Quy ước đóng góp

- **Kotlin style:** Theo Android Kotlin Style Guide
- **KDoc:** Bắt buộc với mọi class/method public. Format:
  ```kotlin
  /**
   * Mô tả ngắn gọn chức năng.
   *
   * @param paramName Mô tả tham số.
   * @return Mô tả giá trị trả về.
   * @throws ExceptionClass Khi nào exception xảy ra.
   */
  ```
- **Commit message:** `<type>: <mô tả ngắn>` (vd: `feat: add TextRunner`, `fix: image runner OOM`)
- **Branch:** `feature/<tên>`, `fix/<tên>`, tuân theo GitFlow đơn giản
