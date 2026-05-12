# Phase 1 Implementation Guide — Personal Phone Agent (PPA)

> Version 1.0 | Last updated: 2026-05-12 | Status: Core Agent hoàn chỉnh

## 1. Overview

Phase 1 (Core Agent) là nền tảng đầu tiên của PPA — một trợ lý cá nhân on-device chạy ngầm 24/7 trên Android. Giai đoạn này tập trung vào pipeline xử lý task bất đồng bộ, trigger từ sự kiện hệ thống, và dashboard tự cập nhật.

**Tóm tắt năng lực Phase 1:**
- Foreground service chạy vĩnh viễn trên Android
- Hàng đợi task bền vững (SQLite + Room)
- Tự động phát hiện ảnh mới từ camera (ContentObserver)
- Runner xử lý ảnh (resize ≤1080px, tổ chức thư mục, xóa ảnh gốc)
- Dashboard UI reactive với Room Flow
- Health monitoring (pin, nhiệt, RAM, network)

---

## 2. Kiến trúc Phase 1

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PERSONAL PHONE AGENT — PHASE 1                    │
│                                                                      │
│  TRIGGERS                  TASK QUEUE               EXECUTION        │
│                                                                      │
│  ┌──────────────┐         ┌──────────────┐        ┌──────────────┐  │
│  │ Camera app   │────┐    │              │        │              │  │
│  │ (new photo)  │    │    │  Room DB     │        │ TaskExecutor │  │
│  └──────────────┘    │    │  (SQLite)    │        │              │  │
│                      ├───→│              │───────→│ ImageRunner  │  │
│  ┌──────────────┐    │    │ personal_    │        │              │  │
│  │ Future:      │    │    │ tasks table  │        │ TEXT stub    │  │
│  │ SMS trigger  │    │    │              │        │ AI stub      │  │
│  │ Notification │    │    └──────┬───────┘        │ AUTO stub    │  │
│  └──────────────┘    │         │                 └──────┬───────┘  │
│                      │         │                        │          │
│  ┌──────────────┐    │    ┌────▼───────┐         ┌──────▼───────┐  │
│  │ PhotoTrigger │────┘    │TaskPolling │         │ Output:      │  │
│  │ Observer     │         │   Loop     │         │ PPA/YYYY-MM/ │  │
│  └──────────────┘         └────────────┘         │ IMG_*.jpg    │  │
│                                                    └──────────────┘  │
│                                                                      │
│  DASHBOARD                                                           │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  MainActivity                                                 │   │
│  │  ┌────────────┐  ┌──────────────────┐  ┌──────────────────┐  │   │
│  │  │ Agent      │  │ Pending Tasks    │  │ Recent History   │  │   │
│  │  │ Status ●   │  │ 📷 IMAGE  P10    │  │ ✅ 📷 10m ago    │  │   │
│  │  │            │  │ 📝 TEXT   P3     │  │ ❌ 📝 1h ago     │  │   │
│  │  └────────────┘  └──────────────────┘  └──────────────────┘  │   │
│  │          [Start Agent]              [Stop Agent]              │   │
│  └──────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### 2.1 Data Flow: photo → processed output

```text
Camera app saves photo
  │
  ▼
MediaStore notifies EXTERNAL_CONTENT_URI
  │
  ▼
PhotoTriggerObserver.onChange() [HandlerThread]
  │  queryLatestPhoto() via ContentResolver
  ▼
PersonalTaskDao.insert(task) [Dispatchers.IO]
  │  type="IMAGE", priority=10, payloadJson={imageId, path, mimeType}
  ▼
Room DB triggers Flow → Dashboard auto-refresh (MainActivity)
  │
  ▼
TaskPollingLoop polls getOldestPending(1) [every 5s]
  │  checks: drainMode, isBusy, battery≥20%, thermal<HOT
  ▼
TaskExecutor.execute(task)
  │  dispatches IMAGE → ImageRunner
  ▼
ImageRunner pipeline:
  1. Parse payload (imageId, path, mimeType, deleteOriginal)
  2. Load bitmap (ContentResolver URI → file path fallback)
  3. Resize to ≤1080px (aspect ratio preserved)
  4. Save to Pictures/PPA/YYYY-MM/IMG_<timestamp>.jpg (JPEG Q80)
  5. Delete original (ContentResolver → File.delete fallback)
  6. Return ExecutionResult(success, updatedPayload)
  │
  ▼
TaskPollingLoop:
  ├─ success → dao.completeTask("DONE", now) + dao.updatePayload(outputPath)
  └─ failure → dao.updateStatus("FAILED")
```

---

## 3. Cấu trúc dự án

```
personal-agent/
├── app/
│   ├── build.gradle.kts                 # AGP 8.2, Kotlin 1.9, Room 2.6, KSP
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/personalagent/
│       │   ├── MainActivity.kt          # Dashboard UI (programmatic, Flow-reactive)
│       │   ├── WorkerApp.kt             # Application class (DB init, Timber)
│       │   ├── WorkerForegroundService.kt # Foreground service host
│       │   ├── agent/
│       │   │   ├── PersonalTask.kt      # Room @Entity (personal_tasks table)
│       │   │   ├── PersonalTaskDao.kt   # Room @Dao (CRUD + Flow queries)
│       │   │   ├── TaskExecutor.kt      # Dispatches task → runner by type
│       │   │   ├── TaskPollingLoop.kt   # Coroutine loop: poll → execute → complete
│       │   │   ├── ImageRunner.kt       # Image resize + organize pipeline
│       │   │   └── PhotoTriggerObserver.kt # ContentObserver for new photos
│       │   ├── config/
│       │   │   ├── AppDatabase.kt       # Room @Database singleton
│       │   │   └── WorkerConfig.kt      # SharedPreferences-backed config
│       │   ├── health/
│       │   │   └── HealthCollector.kt   # Battery, thermal, RAM, network snapshot
│       │   ├── identity/
│       │   │   └── DeviceInfoCollector.kt # Static device info at startup
│       │   └── lifecycle/
│       │       └── ServiceLifecycle.kt  # Start/stop agent loops + observer
│       └── res/values/
│           └── strings.xml
├── docs/
│   ├── phase-1-guide.md         ← Tài liệu này
│   ├── dev-quickstart.md        ← Developer quickstart
│   ├── models.md                ← AI model selection strategy
│   └── PPA-Architecture.md      ← Kiến trúc tổng thể (tham khảo)
├── gradle/
│   └── libs.versions.toml       # Version catalog
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 4. Database Schema

### 4.1 Table: `personal_tasks`

| Column | Type | Mô tả | Default |
|---|---|---|---|
| `id` | TEXT (PK) | UUID v4 — unique task identifier | Generated by creator |
| `type` | TEXT | Task type: `IMAGE`, `TEXT`, `AI`, `AUTO` | Required |
| `payload_json` | TEXT | JSON payload — schema varies by type | Required |
| `priority` | INTEGER | Priority (higher = more urgent) | `0` |
| `status` | TEXT | Lifecycle: `PENDING` → `RUNNING` → `DONE` / `FAILED` | `"PENDING"` |
| `created_at` | INTEGER | Epoch millis when task was created | `System.currentTimeMillis()` |
| `scheduled_at` | INTEGER | (Future) Scheduled execution time | `null` |
| `completed_at` | INTEGER | Epoch millis when task finished | `null` |

### 4.2 Task State Machine

```text
                 ┌─────────┐
                 │ PENDING │  ← inserted by trigger (PhotoTriggerObserver)
                 └────┬────┘
                      │ TaskPollingLoop picks it up
                      ▼
                 ┌─────────┐
                 │ RUNNING │  ← set by TaskPollingLoop before execution
                 └────┬────┘
                      │ ImageRunner.execute()
              ┌───────┴───────┐
              ▼               ▼
         ┌─────────┐    ┌─────────┐
         │  DONE   │    │ FAILED  │
         └─────────┘    └─────────┘
         (completed_at    (stays in DB
          set to now)      for debugging)
```

### 4.3 Payload Schema by Type

**IMAGE payload** (created by `PhotoTriggerObserver`):
```json
{
  "imageId": 12345,
  "path": "/storage/emulated/0/DCIM/Camera/IMG_20260512_120000.jpg",
  "mimeType": "image/jpeg",
  "deleteOriginal": false,
  "outputPath": "/storage/emulated/0/Pictures/PPA/2026-05/IMG_1715500000.jpg",
  "outputSizeBytes": 245760
}
```
(`outputPath` và `outputSizeBytes` được thêm bởi `ImageRunner.buildUpdatedPayload()` sau khi xử lý)

**TEXT / AI / AUTO payload** — Định nghĩa ở Phase 2.

---

## 5. Component Details

### 5.1 WorkerApp (Application)

Điểm vào ứng dụng. Khởi tạo Timber (debug logging) và Room database dưới dạng singleton.

```kotlin
// Truy cập DB từ mọi thành phần
val dao = WorkerApp.db.personalTaskDao()
```

### 5.2 ServiceLifecycle

Quản lý vòng đời agent: khởi động `TaskPollingLoop` và `PhotoTriggerObserver`.

**start():**
1. Load `WorkerConfig` từ SharedPreferences
2. Collect `DeviceInfo` và `HealthSnapshot`
3. Register `PhotoTriggerObserver` trên `HandlerThread` riêng
4. Launch `taskPollingLoop()` coroutine

**stop():**
1. Unregister ContentObserver
2. Cancel task polling coroutine

### 5.3 PhotoTriggerObserver

Đăng ký với `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`, phát hiện ảnh mới ngay khi camera lưu, và enqueue `PersonalTask(type="IMAGE", priority=10)`.

**Thread safety:** Callback chạy trên `HandlerThread` riêng. DB insert dispatched sang `Dispatchers.IO`.

### 5.4 TaskPollingLoop

Coroutine loop chạy mãi mãi, poll database mỗi N giây.

**Logic mỗi chu kỳ:**
1. Check `drainMode` → nếu active, stop loop
2. Check `isBusy` → nếu đang chạy task, skip
3. Check health gate: battery ≥ 20% OR charging; thermal < HOT
4. Query `getOldestPending(1)` từ Room
5. Set `isBusy = true`, launch coroutine:
   - `dao.updateStatus("RUNNING")`
   - `TaskExecutor.execute(task)`
   - Success → `dao.completeTask("DONE", now)` + `dao.updatePayload()`
   - Failure → `dao.updateStatus("FAILED")`
6. Set `isBusy = false`

### 5.5 TaskExecutor

Dispatcher pattern: route task theo `type` đến runner tương ứng.

| Task Type | Runner | Phase 1 Status |
|---|---|---|
| `IMAGE` | `ImageRunner` | ✅ Implemented |
| `TEXT` | (stub) | ⏳ 2s delay, returns success |
| `AI` | (stub) | ⏳ 2s delay, returns success |
| `AUTO` | (stub) | ⏳ 2s delay, returns success |
| Unknown | — | ❌ Returns `ExecutionResult(false)` |

### 5.6 ImageRunner

Pipeline xử lý ảnh (chi tiết ở §2.1):

- **Input:**
  - `imageId` + `path` → tải từ ContentResolver URI, fallback file path
  - `mimeType` → xác định output format
  - `deleteOriginal` (mặc định `false`)
- **Resize:** max 1080px cạnh dài nhất, giữ nguyên aspect ratio
- **Output:** `Pictures/PPA/YYYY-MM/IMG_<epoch>.jpg`, JPEG quality 80
- **Cleanup:** Xóa ảnh gốc qua ContentResolver (ưu tiên) hoặc File.delete (fallback)

### 5.7 MainActivity (Dashboard)

UI programmatic, không XML. 4 section:

1. **Agent Status Bar:** ● xanh (running) / ● đỏ (stopped)
2. **Pending Tasks:** top 20 PENDING tasks, real-time qua `getPendingTasksFlow()`
3. **Recent History:** last 10 DONE/FAILED tasks, real-time qua `getRecentTasksFlow()`
4. **Control Buttons:** Start Agent / Stop Agent

**Reactive update:** Room `Flow<>` queries → `collectLatest {}` → UI auto-refresh khi DB thay đổi.

### 5.8 HealthCollector

Snapshot health trước mỗi chu kỳ poll:
- **Battery:** percent + charging state (BatteryManager intent)
- **Thermal:** 7-level map từ PowerManager API (Android 10+)
- **RAM:** availMem từ ActivityManager
- **Storage:** free bytes từ StatFs
- **Network:** reachable check qua ConnectivityManager

### 5.9 WorkerConfig

Cấu hình lưu trong SharedPreferences (`personal_agent_config`):

| Field | Key | Default | Mô tả |
|---|---|---|---|
| `taskPollIntervalSec` | `poll_interval_sec` | `5` | Giây giữa các chu kỳ poll |
| `minBatteryPercent` | `min_battery_pct` | `20` | Pin dưới ngưỡng này → pause poll |
| `minRamMb` | `min_ram_mb` | `200` | RAM tối thiểu (MB) để chạy task |
| `thermalLimit` | `thermal_limit` | `"THERMAL_WARM"` | Thermal tối đa cho phép chạy task |

---

## 6. Configuration Reference

### 6.1 WorkerConfig defaults

```kotlin
WorkerConfig(
    taskPollIntervalSec = 5,   // Poll mỗi 5 giây
    minBatteryPercent = 20,    // Pause khi pin < 20% (trừ khi đang sạc)
    minRamMb = 200,            // Pause khi RAM < 200 MB
    thermalLimit = "THERMAL_WARM", // Pause khi thermal ≥ HOT
)
```

### 6.2 Android Manifest Permissions

| Permission | Purpose | API Level |
|---|---|---|
| `INTERNET` | (Future) gRPC/HTTP connectivity | All |
| `READ_EXTERNAL_STORAGE` | MediaStore access for photo trigger | ≤32 |
| `READ_MEDIA_IMAGES` | Scoped storage image access | ≥33 |
| `FOREGROUND_SERVICE` | Persistent worker service | All |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Special use foreground type | ≥34 |
| `BATTERY_STATS` | Battery percentage collection | All |
| `POWER_THERMAL` | Thermal status reading | All |
| `WAKE_LOCK` | (Future) Keep CPU awake during tasks | All |
| `RECEIVE_BOOT_COMPLETED` | (Future) Auto-start on boot | All |

### 6.3 Build Configuration

| Setting | Value |
|---|---|
| compileSdk | 34 |
| minSdk | 29 (Android 10) |
| targetSdk | 34 |
| Kotlin | 1.9.22 |
| AGP | 8.2.2 |
| Room | 2.6.1 |
| Coroutines | 1.8.0 |
| KSP | 1.9.22-1.0.17 |

---

## 7. Output Structure

```
Pictures/PPA/
├── 2026-05/
│   ├── IMG_1715500000123.jpg   (≤1080px, JPEG Q80)
│   ├── IMG_1715500000456.jpg
│   └── IMG_1715500000789.jpg
└── 2026-04/
    └── IMG_1715400000000.jpg
```

- Mỗi ảnh resize lưu vào `Pictures/PPA/YYYY-MM/` (tổ chức theo tháng)
- Tên file: `IMG_<epochMillis>.<ext>` (`.jpg` mặc định, `.png` nếu input là PNG)
- Ảnh gốc: vẫn tồn tại trong DCIM/Camera trừ khi `deleteOriginal = true`

---

## 8. Threading Model

| Component | Thread | Cơ chế |
|---|---|---|
| PhotoTriggerObserver.onChange() | HandlerThread | android.os.Handler |
| PhotoTriggerObserver DB insert | Dispatchers.IO | CoroutineScope.launch |
| TaskPollingLoop | Dispatchers.Default | Coroutine scope từ ServiceLifecycle |
| TaskExecutor.execute() | Runner quyết định | Dispatchers.IO (ImageRunner) |
| MainActivity Flow collect | Dispatchers.Main | CoroutineScope riêng |
| ServiceLifecycle start/stop | Main thread | Gọi từ Service.onCreate/onDestroy |

---

## 9. Known Limitations (Phase 1)

1. **Chạy 1 task / thời điểm** — `isBusy` AtomicBoolean ngăn chạy song song. Giới hạn này phù hợp với thiết bị Android tầm trung.
2. **Không có retry tự động** — Task FAILED không được tự động retry. Sẽ bổ sung ở Phase 2.
3. **Không có persistence cho task đang chạy** — Nếu app bị kill giữa chừng, task đang RUNNING sẽ stuck. Cần thêm recovery logic.
4. **TEXT/AI/AUTO là stub** — Chỉ IMAGE được implement thực sự.
5. **UI đơn giản** — Không có detail view cho từng task, không có filter/sort.
6. **Không có notification khi task hoàn tất** — Chỉ hiển thị trong dashboard.

---

## 10. Future: Phase 2 Planned Additions

- **AIRunner:** ModelPool với LRU cache, TFLite/ONNX inference, cloud fallback (xem `docs/models.md`)
- **TextRunner:** Summarise, classify văn bản
- **AutoRunner:** Action chain / macros tự động
- **Retry engine:** Tự động retry task FAILED (configurable limit)
- **Scheduled tasks:** `scheduled_at` field, delayed execution
- **Boot receiver:** Tự động start service khi boot
- **Notification output:** Notify user khi task hoàn tất
- **Cloud backup:** Sync task history lên cloud

---

## 11. Tham chiếu

| Tài liệu | Path |
|---|---|
| Dev Quickstart | `docs/dev-quickstart.md` |
| AI Model Strategy | `docs/models.md` |
| Tổng quan kiến trúc | `docs/PPA-Architecture.md` |
| Project README | `README.md` |
