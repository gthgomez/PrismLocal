# LLM Host Android (Prism Local) — QA Checklist

---

## 1. JNI Bridge

- [ ] Native library loads without crash
- [ ] Cancellation is mutex-safe
- [ ] Lifecycle fail-closed (release on destroy)
- [ ] MemoryGovernor responds to pressure
- [ ] Error propagation to Kotlin works

**Pass criteria: 5/5**

---

## 2. Inference

- [ ] Model loads successfully
- [ ] Generates coherent tokens
- [ ] Stops on cancel within 2s
- [ ] Memory stays within budget
- [ ] Context window respected
- [ ] Temperature/sampling params work

**Pass criteria: 6/6**

---

## 3. Model Storage

- [ ] SHA-256 manifest validated on download
- [ ] HuggingFace download works
- [ ] App-owned directory permissions correct
- [ ] Corrupted model rejected

**Pass criteria: 4/4**

---

## 4. Foreground Service

- [ ] InferenceService starts correctly
- [ ] Shows required notification
- [ ] Stops on task removal
- [ ] Survives configuration change

**Pass criteria: 4/4**

---

## 5. UI

- [ ] Chat interface scrolls
- [ ] Model selection switches models
- [ ] Settings persist
- [ ] Error states shown
- [ ] Loading indicators

**Pass criteria: 5/5**

---

## 6. Permissions

- [ ] FOREGROUND_SERVICE declared
- [ ] INTERNET declared
- [ ] No unnecessary permissions

**Pass criteria: 3/3**

---

## 7. Go / No-Go Gate

**Ship when all items pass.**

- [ ] C++ layer crash doesn't take down app
- [ ] Model storage doesn't leak to other apps
- [ ] Real inference requires device evidence per PROJECT_CONTEXT.md
