# Phase 2 Implementation Guide — AIRunner + ModelPool + Pre-warm

> Personal Phone Agent | Phase 2 | Version 1.0 | 2026-05-12

## 1. Overview

Phase 2 bổ sung khả năng AI inference on-device thông qua ModelPool — một hệ thống quản lý model thông minh với LRU cache, health gate, và predictive pre-warming. Toàn bộ pipeline được tích hợp vào `TaskExecutor` và `TaskPollingLoop` đã có từ Phase 1.

**Tóm tắt năng lực Phase 2:**
- 7 AI model được đăng ký (2 resident + 5 on-demand)
- LRU cache quản lý RAM cho model (cap 4 GB, resident không bị evict)
- Health gate chặn model nặng (>2 GB) khi pin thấp / nhiệt cao
- Pre-warm theo thời gian trong ngày (sáng → dịch, tối → vision, đêm → code/math)
- AIRunner dispatcher với 5 subtype inference stubs
- ModelLoader stub (sẵn sàng cho TFLite/ONNX ở Phase 3)

---

## 2. Kiến trúc Phase 2

```
┌──────────────────────────────────────────────────────────────────────────┐
│                    PERSONAL PHONE AGENT — PHASE 2                         │
│                                                                           │
│  TRIGGER                  EXECUTION                  AI MODEL POOL         │
│                                                                           │
│  ┌──────────┐            ┌──────────────┐          ┌──────────────────┐  │
│  │ Camera   │──IMAGE────→│              │          │                  │  │
│  └──────────┘            │              │          │  ModelRegistry   │  │
│                           │ TaskExecutor │──AI────→│  (7 models)      │  │
│  ┌──────────┐            │              │          │                  │  │
│  │AiTest    │──AI───────→│  IMAGE→ImgR  │          │  resolve() →     │  │
│  │Helper    │            │  AI→AIRunner │          │  ModelMeta       │  │
│  └──────────┘            │  TEXT→stub   │          └────────┬─────────┘  │
│                           │  AUTO→stub   │                   │            │
│  ┌──────────┐            └──────────────┘          ┌────────▼─────────┐  │
│  │Preloader │                                       │                  │  │
│  │(periodic)│──pre-warm────────────────────────────→│  ModelCache      │  │
│  └──────────┘                                       │  (LRU, 4GB cap)  │  │
│                                                      │                  │  │
│                                                      │  get() → health  │  │
│                                                      │  gate → evict    │  │
│                                                      │  → load → cache  │  │
│                                                      └────────┬─────────┘  │
│                                                               │            │
│                                                      ┌────────▼─────────┐  │
│                                                      │  ModelLoader     │  │
│                                                      │  (stub Phase 2)  │  │
│                                                      │  → null (stub)   │  │
│                                                      └──────────────────┘  │
│                                                                           │
│  PRE-WARM SCHEDULER (ModelPreloader)                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐ │
│  │  Startup:     preload BGE-small + Whisper-small (resident, ~1.1 GB) │ │
│  │  06:00-10:00: preload Hy-MT1.5 (translate)                         │ │
│  │  10:00-18:00: idle (LRU handles naturally)                         │ │
│  │  18:00-22:00: preload Florence-2 (vision)                          │ │
│  │  22:00-06:00: preload Qwen-Coder + Qwen-Math (if healthy)          │ │
│  │  When idle:   preload next priority on-demand model               │ │
│  └─────────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 3. ModelPool Design

### 3.1 ModelMeta

Cấu trúc dữ liệu mô tả một AI model trong registry:

| Field | Type | Mô tả |
|---|---|---|
| `name` | String | Unique ID (e.g., `"hy-mt1.5"`) |
| `displayName` | String | Tên hiển thị (e.g., `"Hy-MT1.5-1.8B"`) |
| `sizeBytes` | Long | RAM footprint ước tính |
| `assetPath` | String | Đường dẫn dưới `assets/models/` |
| `engine` | String | `"TFLite"` hoặc `"ONNX"` |
| `taskSubtypes` | List\<String\> | Các subtype model này phục vụ |
| `priority` | Int | Ưu tiên load (1 = cao nhất) |
| `resident` | Boolean | Nếu `true`: luôn tải, không bao giờ bị evict |
| `requiresHealthyDevice` | Boolean | Nếu `true`: health gate enforced |

### 3.2 ModelRegistry — 7 Models

| # | Model | Size | Resident | Subtypes | Engine | Health Gate |
|---|-------|------|----------|----------|--------|-------------|
| 1 | BGE-small | 600 MB | ✅ | embedding | ONNX | No |
| 2 | Whisper-small | 500 MB | ✅ | stt | ONNX | No |
| 3 | Florence-2-0.77B | 1.5 GB | — | vision, ocr | TFLite | No |
| 4 | Hy-MT1.5-1.8B | 574 MB | — | translate | TFLite | No |
| 5 | CodeS-1B | 2.0 GB | — | sql | TFLite | No |
| 6 | Qwen2.5-Coder-1.5B | 3.0 GB | — | code | ONNX | **Yes** |
| 7 | Qwen2.5-Math-1.5B | 3.0 GB | — | math | ONNX | **Yes** |

**API công khai:**
- `resolve(taskType, payloadJson)` → `ModelMeta?` — map subtype → model (1:1)
- `getByName(name)` → `ModelMeta?`
- `getResidentModels()` → `List<ModelMeta>` (BGE + Whisper)
- `getOnDemandModels()` → `List<ModelMeta>` (sorted by priority)
- `getAllModels()` → `List<ModelMeta>`

### 3.3 ModelCache — Health-Aware LRU

**Memory budget (8 GB device):**

```
┌──────────────────────────────────────────┐
│              RAM Pool (8 GB)              │
│──────────────────────────────────────────│
│  Resident:                               │
│    BGE-small           600 MB            │
│    Whisper-small       500 MB            │
│    → Total:           ~1.1 GB (never     │
│                               evicted)   │
│──────────────────────────────────────────│
│  On-demand pool (LRU):                   │
│    Max available:     2.9 GB             │
│    Currently loaded:  varies             │
│──────────────────────────────────────────│
│  Hard cap:            4.0 GB total       │
│  System overhead:     ~4.0 GB (OS/app)   │
└──────────────────────────────────────────┘
```

**LRU Eviction:**
- `LinkedHashMap` insertion-ordered
- Evict oldest non-resident model first
- Resident models (BGE + Whisper) **never** evicted
- `evict(toFreeBytes)` → quét từ cũ nhất → xóa đến khi đủ dung lượng

**Health Gate (models > 2 GB):**

| Condition | Gate |
|---|---|
| Battery ≥ 50% OR charging | ✅ Pass |
| Battery < 50% AND not charging | ❌ `ModelLoadException` |
| Thermal ≤ WARM | ✅ Pass |
| Thermal ≥ HOT | ❌ `ModelLoadException` |

**API:**
- `get(context, meta)` → `Any?` — auto load/evict, throws `ModelLoadException` nếu health gate block
- `preloadResidentModels(context)` — called at startup
- `isLoaded(name)` → `Boolean`
- `getStats()` → `CacheStats` (memory, counts)
- `evictAll()` — evict all non-resident models

### 3.4 ModelLoader

Stub trong Phase 2. Luôn trả `null`. Phase 3 sẽ khởi tạo TFLite `Interpreter` hoặc ONNX `OrtSession`.

- `load(meta)` → `null` (stub)
- `getModelSize(meta)` → `meta.sizeBytes` (authoritative for memory accounting)
- `isModelAvailable(meta)` → `false` (stub)

---

## 4. AIRunner Pipeline

### 4.1 Full flow

```
PersonalTask(type="AI", payloadJson={"subtype":"translate","input":"xin chào"})
  │
  ▼
TaskExecutor.execute(task) → AIRunner(context, task).execute()
  │
  ├── 1. parsePayload() → subtype="translate", input="xin chào"
  │
  ├── 2. ModelRegistry.resolve("AI", payloadJson)
  │         → subtype "translate" → Hy-MT1.5-1.8B (574 MB, health gate: No)
  │
  ├── 3. ModelCache.get(context, meta)
  │         ├── cache hit? → bump LRU position → return cached model
  │         └── cache miss? → health gate check → evict if needed → load → cache
  │
  ├── 4. runInference(subtype, input, params)
  │         → stub inference with realistic latency (1-3s)
  │         → "[STUB] translated (vi→en): xin chào"
  │
  └── 5. buildResultPayload()
            → {"output":"[STUB] translated...", "model":"hy-mt1.5",
                "latencyMs":1234, "subtype":"translate", "summary":"..."}
```

### 4.2 Subtype → Stub mapping

| Subtype | Stub Delay | Result Format |
|---|---|---|
| `translate` | 1-3s | `"[STUB] translated (vi→en): {input}"` |
| `vision` | 0.5-2s | `"[STUB] vision: detected 'cat' (confidence=0.94)"` |
| `sql` | 1.5-3.5s | `"SELECT id, name, created_at FROM stub_table WHERE..."` |
| `code` | 2-5s | `"// [STUB] generated Kotlin function for: {input}"` |
| `math` | 0.5-1.5s | `"[STUB] answer: 42 (parsed: {input})"` |

### 4.3 Output payload

```json
{
  "output": "[STUB] translated (vi→en): xin chào",
  "model": "hy-mt1.5",
  "latencyMs": 1234,
  "subtype": "translate",
  "summary": "translate: [STUB] translated (vi→en): xin chào"
}
```

Payload này được persist vào DB qua `PersonalTaskDao.updatePayload()` sau khi task hoàn tất.

---

## 5. Pre-warm Strategy

### 5.1 ModelPreloader

Object singleton với 3 chiến lược preload:

**1. preloadResidents(cache, context)** — Startup
- Gọi một lần khi ServiceLifecycle.start()
- Load BGE-small + Whisper-small (~1.1 GB)
- Idempotent: bỏ qua nếu đã loaded
- Không bị health gate (resident models)

**2. preloadByTimeOfDay(cache, context)** — Định kỳ (mỗi 30 phút)

| Thời gian | Window | Model preload | Rationale |
|---|---|---|---|
| 06:00–10:00 | Morning | Hy-MT1.5 (translate) | Foreign news reading |
| 10:00–18:00 | Day | None | LRU handles naturally |
| 18:00–22:00 | Evening | Florence-2 (vision) | Photo organization |
| 22:00–06:00 | Night | Qwen-Coder + Qwen-Math | Heavy work, health-gated |

Health gates ARE enforced for heavy models (Qwen-Coder, Qwen-Math). Nếu pin < 50% và không sạc → `ModelLoadException` → graceful skip.

**3. preloadIfIdle(cache, context, isBusy)** — Cơ hội
- Chỉ chạy khi `isBusy == false`
- Chọn model on-demand ưu tiên cao nhất chưa loaded, vừa với RAM còn trống
- Tuân thủ health gate như preloadByTimeOfDay

### 5.2 PreloadReport

Mỗi lần preload trả về `PreloadReport` với:
- `source`: `"residents"`, `"time-of-day (morning)"`, `"idle"`
- `modelsAttempted`: danh sách model đã thử load
- `modelsPreloaded`: danh sách model đã load thành công
- `memoryDelta`: ±bytes thay đổi
- `cacheStats`: snapshot cache sau preload
- `summary()`: human-readable one-liner

---

## 6. Integration with TaskExecutor & Polling Loop

### 6.1 TaskExecutor dispatch

```kotlin
"AI" -> {
    val runner = AIRunner(context, task)
    val ok = runner.execute()
    if (ok) {
        ExecutionResult(success = true, outputPayload = runner.buildResultPayload())
    } else {
        ExecutionResult(success = false)
    }
}
```

### 6.2 TaskPollingLoop

Không cần thay đổi — loop xử lý task hoàn toàn generic:
- `TaskExecutor(context)` — context được truyền vào, AIRunner dùng để health check
- `executor.execute(task)` — hoạt động với mọi task type
- `dao.completeTask("DONE", now)` + `dao.updatePayload(outputPayload)` — payload AI được persist

### 6.3 ServiceLifecycle integration

```kotlin
fun start() {
    // ... Phase 1 initialization ...

    // Preload resident models at startup
    if (config.modelPreloadEnabled) {
        val cache = ModelCache.getInstance()
        ModelPreloader.preloadResidents(cache, context)
    }

    // Start periodic preload job
    startPreloadJob(config.preloadIntervalMin)

    // ... launch taskPollingLoop ...
}
```

---

## 7. Configuration Reference (Phase 2 additions)

Thêm vào `WorkerConfig`:

| Field | Key | Default | Mô tả |
|---|---|---|---|
| `maxModelRamMb` | `max_model_ram_mb` | `4096` | RAM tối đa cho model (4 GB) |
| `modelPreloadEnabled` | `model_preload_enabled` | `true` | Bật/tắt preload resident models |
| `modelHealthGateEnabled` | `model_health_gate_enabled` | `true` | Bật/tắt health gate cho model nặng |
| `aiInferenceTimeoutSec` | `ai_inference_timeout_sec` | `300` | Timeout inference (5 phút) |
| `preloadIntervalMin` | `preload_interval_min` | `30` | Chu kỳ kiểm tra preload (phút) |

---

## 8. Model Directory Structure

```
app/src/main/assets/models/
├── bge-small/.gitkeep              ← resident, 600 MB
├── whisper-small/.gitkeep          ← resident, 500 MB
├── florence-2/.gitkeep             ← vision, 1.5 GB
├── hy-mt1.5/.gitkeep               ← translate, 574 MB
├── codes-1b/.gitkeep               ← sql, 2.0 GB
├── qwen-coder-1.5b/.gitkeep        ← code, 3.0 GB (health-gated)
└── qwen-math-1.5b/.gitkeep         ← math, 3.0 GB (health-gated)
```

Hiện tại chỉ có `.gitkeep` placeholders. Model files thực tế (`.tflite`, `.onnx`) sẽ được thêm vào Phase 3.

---

## 9. AiTestHelper — Dev Testing Utility

Class tiện ích cho developer test AI pipeline không cần trigger thật:

```kotlin
AiTestHelper.enabled = true  // BẮT BUỘC — mặc định false để an toàn

// Insert các loại AI task
AiTestHelper.insertTranslateTask(dao, "xin chào", "vi", "en")
AiTestHelper.insertVisionTask(dao, "image_12345")
AiTestHelper.insertSqlTask(dao, "Show all active users")
AiTestHelper.insertCodeTask(dao, "Reverse a string")
AiTestHelper.insertMathTask(dao, "2 + 2")
```

Mỗi method tạo `PersonalTask(type="AI")` với payload JSON đúng schema subtype.

---

## 10. End-to-End AI Task Flow

```
AiTestHelper.insertTranslateTask(dao, "xin chào", "vi", "en")
  │
  ▼
Room DB: PersonalTask(type="AI", payload={"subtype":"translate","input":"xin chào","params":{"sourceLang":"vi","targetLang":"en"}})
  │
  ▼
TaskPollingLoop.getOldestPending(1) → picks up task
  │
  ▼
dao.updateStatus("RUNNING")
  │
  ▼
TaskExecutor.execute(task)
  │
  ▼
AIRunner(context, task).execute()
  ├── parsePayload()     → subtype="translate", input="xin chào"
  ├── ModelRegistry.resolve() → Hy-MT1.5-1.8B (574 MB, health gate: No)
  ├── ModelCache.get(context, meta)
  │     ├── Cache miss → memory check OK (574MB < 2.9GB available) → no eviction needed
  │     ├── Health gate: meta.requiresHealthyDevice == false → skip
  │     └── ModelLoader.load() → null (stub) → cached as "stubbed"
  ├── runInference("translate", "xin chào", {vi, en})
  │     └── delay(1-3s) → "[STUB] translated (vi→en): xin chào"
  └── latencyMs = 1234 → ExecutionResult(true, resultPayload)
  │
  ▼
dao.completeTask("DONE", now)
dao.updatePayload(resultPayload)  // {output, model, latencyMs, subtype, summary}
  │
  ▼
Dashboard: task moves from Pending → disappears, appears in Recent History ✅
```

---

## 11. Testing

### 11.1 Enable AiTestHelper

```kotlin
// Trong MainActivity.startAgent() hoặc adb shell
AiTestHelper.enabled = true
val dao = WorkerApp.db.personalTaskDao()
AiTestHelper.insertTranslateTask(dao, "xin chào", "vi", "en")
```

### 11.2 Verify via logcat

```bash
adb logcat | grep -E "AIRunner|ModelCache|ModelRegistry|ModelPreloader"
```

Expected log sequence:
```
ModelRegistry: subtype=translate → Hy-MT1.5-1.8B (TFLite, 574MB, resident=false)
ModelCache: miss model=hy-mt1.5 (574MB), loading...
ModelLoader: stub mode — hy-mt1.5 not actually loaded
ModelCache: stubbed model=hy-mt1.5 (574MB, total=574MB, resident=0MB)
AIRunner: inference MOCK subtype=translate inputLen=9
AIRunner: inference complete subtype=translate latency=1234ms
```

### 11.3 Test health gate

```bash
# Giả lập pin thấp (cần root hoặc mock)
# Khi pin < 50% và không sạc, Qwen-Coder/Math sẽ bị block:
AiTestHelper.insertCodeTask(dao, "Reverse a string")
# Log: ModelCache: health gate blocked model=qwen-coder-1.5b batt=30% charging=false thermal=THERMAL_NORMAL
# AIRunner: health gate blocked model=qwen-coder-1.5b
# Task → FAILED
```

---

## 12. Phase 2 vs Phase 1 Comparison

| Component | Phase 1 | Phase 2 |
|---|---|---|
| Task types | IMAGE (real), TEXT/AI/AUTO (stub) | IMAGE (real), AI (stub via ModelPool), TEXT/AUTO (stub) |
| ImageRunner | ✅ Full pipeline | ✅ Unchanged |
| AI inference | ❌ 2s delay stub | ✅ ModelPool + 5 subtype stubs |
| Model management | — | ✅ 7 models, LRU cache, health gate |
| Pre-warm | — | ✅ Time-of-day + idle preloading |
| Config fields | 4 | 11 (thêm 7 field model) |

---

## 13. Future: Phase 3 Planned Work

- Tải model files thực tế (`.tflite`, `.onnx`) vào `assets/models/`
- `ModelLoader.load()` khởi tạo TFLite `Interpreter` / ONNX `OrtSession`
- Thay thế inference stubs bằng real model execution
- Cloud fallback: nếu model không available on-device → REST API
- Model download / update tự động
- Benchmark latency cho từng model / subtype

---

## 14. Tham chiếu

| Tài liệu | Path |
|---|---|
| Phase 1 Guide | `docs/phase-1-guide.md` |
| Dev Quickstart | `docs/dev-quickstart.md` |
| Model Selection Strategy | `docs/models.md` |
| Project README | `README.md` |
