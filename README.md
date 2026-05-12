# Personal Phone Agent (PPA)

Trợ lý cá nhân on-device, chạy ngầm 24/7 trên Android.

## Trạng thái
- **Sprint 1 (Core Agent)**: đang phát triển — foreground service, SQLite queue, scheduler, 1 runner

## Tài liệu
- `docs/PPA-Architecture.md` — Thiết kế kiến trúc tổng thể
- `docs/models.md` — Chiến lược chọn model AI on-device

## Cấu trúc dự án
```
personal-agent/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/personalagent/
│       │   ├── MainActivity.kt
│       │   ├── WorkerApp.kt
│       │   ├── WorkerForegroundService.kt
│       │   ├── agent/
│       │   │   ├── TaskExecutor.kt
│       │   │   └── TaskPollingLoop.kt
│       │   ├── config/
│       │   │   └── WorkerConfig.kt
│       │   ├── health/
│       │   │   └── HealthCollector.kt
│       │   ├── identity/
│       │   │   └── DeviceInfoCollector.kt
│       │   └── lifecycle/
│       │       └── ServiceLifecycle.kt
│       └── res/values/
│           └── strings.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/
│   └── libs.versions.toml
└── gradle.properties
```

## Bước tiếp theo
1. Thêm Room database (personal_tasks table)
2. Thêm ContentObserver trigger cho ảnh mới
3. Thêm ImageRunner (resize/organize)
4. Dashboard UI đơn giản

---
Xem chi tiết tại `docs/PPA-Architecture.md`.
