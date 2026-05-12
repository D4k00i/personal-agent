# Personal Phone Agent — Model Selection Strategy

Danh sách model do Huy đề xuất, cập nhật theo phản hồi (RAM 8GB, bỏ qua BioMistral-7B, ưu tiên: dịch → vision → SQL → code/math).

> **Phase 2 status:** ModelPool, ModelCache (LRU + health gate), ModelPreloader, và AIRunner (5 inference stubs) đã được implement. Xem `docs/phase-2-guide.md` để biết chi tiết kiến trúc.

## Bảng tổng hợp

| Tác vụ | Model | Size RAM | On-Device khả thi? | Đánh giá |
|---|---:|---:|---:|---|
| 📖 Dịch thuật | Hy-MT1.5-1.8B | 574 MB | ✅ Rất tốt | Nhẹ nhất, tải nhanh, vượt model 72B/32B |
| 💻 Code | Qwen2.5-Coder-1.5B | 3 GB | ⚠️ Có điều kiện | Nặng, chỉ khi cắm sạc + thermal thấp |
| 🔢 Math | Qwen2.5-Math-1.5B | 3 GB | ⚠️ Có điều kiện | Như trên |
| 🗄️ SQL | CodeS-1B | 2 GB | ⚠️ Có điều kiện | Nhẹ hơn Coder, cân nhắc ưu tiên hơn |
| 👁️ Vision | Florence-2-0.77B | 1.5 GB | ✅ Tốt | Đa năng (OCR, caption, detection), ưu tiên cao |
| 🎤 Audio | Whisper-small | 500 MB | ✅ Rất tốt | Chuẩn mực, chạy nền được |
| 📚 Embedding | BGE-small | 600 MB | ✅ Rất tốt | MTEB #1 small, luôn thường trú |

> 🚫 **BioMistral-7B (14 GB)** — Bỏ qua theo yêu cầu của Huy. Nếu sau này cần y tế, sẽ dùng cloud API.

## Chiến lược nạp/xả model (LRU Cache)

RAM thiết bị: **8 GB** (xác nhận từ Huy).

```
┌─────────────────────────────────────────────────────────────┐
│                      RAM Pool (8 GB)                         │
│─────────────────────────────────────────────────────────────│
│  Resident (luôn trong RAM):                                  │
│    BGE-small              600 MB   (embedding, RAG local)    │
│    Whisper-small          500 MB   (audio trigger, STT)      │
│    → Tổng nền:           ~1.1 GB                             │
│─────────────────────────────────────────────────────────────│
│  On-demand Pool (LRU, nạp khi cần):                          │
│    Florence-2             1.5 GB   (vision — ưu tiên 1)      │
│    Hy-MT1.5               574 MB   (dịch — ưu tiên 2)        │
│    CodeS-1B               2.0 GB   (SQL — ưu tiên 3)          │
│    Qwen2.5-Coder          3.0 GB   (code — ưu tiên 4)        │
│    Qwen2.5-Math           3.0 GB   (math — ưu tiên 4)        │
│─────────────────────────────────────────────────────────────│
│  Tổng max tại 1 thời điểm: ~4-5 GB (resident + 1-2 model)  │
│  Còn dư ~3 GB cho hệ thống và app.                          │
└─────────────────────────────────────────────────────────────┘
```

## Quy tắc Scheduler cho Model

1. **Resident models** (BGE-small + Whisper-small): luôn giữ trong RAM, tổng ~1.1 GB
2. **Model > 2 GB** (Coder, Math): chỉ nạp khi **pin ≥ 50% HOẶC đang sạc** + **thermal ≤ WARM**
3. **LRU eviction**: model ít dùng nhất bị đẩy ra khi RAM free < 500 MB
4. **Pre-warm**: tải trước model theo thói quen (vd: sáng hay dịch → preload Hy-MT)
5. **Priority queue**: dịch → vision → SQL → code/math (đúng ý Huy)

## Cấu trúc thư mục model trong project

```
personal-agent/app/src/main/assets/models/
├── bge-small/              (600 MB)   — embedding, luôn tải
├── whisper-small/          (500 MB)   — STT, luôn tải
├── florence-2/             (1.5 GB)   — vision, ưu tiên 1
├── hy-mt1.5/               (574 MB)   — dịch, ưu tiên 2
├── codes-1b/               (2.0 GB)   — SQL, ưu tiên 3
├── qwen-coder-1.5b/        (3.0 GB)   — code, cần sạc
└── qwen-math-1.5b/         (3.0 GB)   — math, cần sạc
```

## Tích hợp vào PPA Architecture

- **AIRunner** mới: quản lý ModelPool (LRU cache), nhận task từ Scheduler, chọn model theo type, nạp/xả động
- **HealthCollector**: cung cấp pin/thermal để Scheduler quyết định có được nạp model nặng không
- **Cloud Fallback**: nếu model quá nặng hoặc không có on-device, gửi qua REST API tới cloud endpoint

---
Tài liệu bổ sung cho PPA-Architecture.md, mục AI Runner.

## Phase 2 Implementation Status (2026-05-12)

| Component | File | Status |
|---|---|---|
| ModelMeta | `agent/model/ModelMeta.kt` | ✅ Implemented — 9-field data class |
| ModelRegistry | `agent/model/ModelRegistry.kt` | ✅ Implemented — 7 models, 5 subtypes |
| ModelCache | `agent/model/ModelCache.kt` | ✅ Implemented — LRU + health gate + eviction |
| ModelLoader | `agent/model/ModelLoader.kt` | ✅ Implemented — stub (returns null, ready for Phase 3) |
| AIRunner | `agent/model/AIRunner.kt` | ✅ Implemented — 5 subtype stubs (1-5s latency) |
| ModelPreloader | `agent/model/ModelPreloader.kt` | ✅ Implemented — time-of-day + idle preload |
| TaskExecutor | `agent/TaskExecutor.kt` | ✅ Updated — AI case routes to AIRunner |
| WorkerConfig | `config/WorkerConfig.kt` | ✅ Updated — 5 model config fields |
| AiTestHelper | `agent/AiTestHelper.kt` | ✅ Implemented — dev testing utility |

### Key Implementation Details

**ModelCache health gate:**
- Models ≤ 2 GB: load anytime
- Models > 2 GB (Qwen-Coder, Qwen-Math): require battery ≥ 50% OR charging, AND thermal ≤ WARM
- Gate failure → `ModelLoadException` → task marked FAILED

**Pre-warm schedule (ModelPreloader):**
- Startup: BGE-small + Whisper-small (resident, ~1.1 GB)
- Morning (06-10): Hy-MT1.5 (translate)
- Evening (18-22): Florence-2 (vision)
- Night (22-06): Qwen-Coder + Qwen-Math (if health gate passes)
- Idle: next priority on-demand model

**Memory budget:**
- Resident: ~1.1 GB (never evicted)
- On-demand pool: ~2.9 GB (LRU eviction)
- Hard cap: 4.0 GB total
- System overhead: ~4.0 GB

See `docs/phase-2-guide.md` for complete architecture documentation.
