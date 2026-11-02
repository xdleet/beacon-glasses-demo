# Architecture — how one tap works

```
Pixel Watch 3                Phone (pocket, Bluetooth)        Home desktop backend
ONE-TAP ASK MUSE  ──Wearable──▶  MessageClient listener
  (watch 2.6.8)                   /beacon/one_tap
                                     │
                                     ▼
                              Glasses preview + snap
                              (Ray-Ban Meta, via phone)
                                     │
                                     ▼
                              Frame + preset question
                              ──LAN HTTP──▶  FastAPI :8901 /ask
                                                    │
                                                    ▼
                                              Muse Wonder
                                              + SAM 3.1 segmentation
                                              Preset question only:
                                              "What am I looking at?
                                               What is wrong with it,
                                               and how do I fix it?"
                                                    │
                                                    ▼
                              Answer ◀──LAN HTTP──  /latest
                                     │
                                     ▼
Watch main transcript  ◀──Wearable──  /beacon/demo_answer
+ notification + Demo screen (history / fallback)
```

## Design decisions

- Phone is a background bridge only, never tapped. That is the whole
  point for house calls: hands full, phone in pocket.
- One preset question only on the one-tap path. No voice input, no
  typed question on this path — one motion, one answer.
- Watch answers land in the MAIN transcript first. Demo is history /
  fallback only, not the primary result path.
- Web Chat was removed Oct 30, 2026 — it was broken since Oct 27.
- Answers are slow on desktop CPU in the text-chat prototype (~2 min
  each in that separate project); the glasses demo backend answers
  through the remote Muse API, not local CPU inference.
- No key lives in the watch or phone app. The backend holds the key
  server-side, on the desktop only.
- Sample data only in the demo. No real customer machines, no private
  data, no real tickets in the demo video or screenshots.
