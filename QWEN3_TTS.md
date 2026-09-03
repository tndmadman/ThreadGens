# Qwen3-TTS in ThreadGens

ThreadGens supports Qwen3-TTS through the `qwen3` TTS engine. The default Windows runner uses `Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice` with the English `Ryan` and `Aiden` speakers.

## Windows setup

Run:

```bat
setup_windows.bat
```

The normal setup now keeps the existing Kokoro/Piper installs and also creates an isolated Qwen environment at:

```text
.venv-qwen3-tts\
```

The Qwen setup installs CUDA-enabled PyTorch, installs `qwen-tts`, verifies that PyTorch can see the NVIDIA GPU, and performs one model-load validation so the model weights are cached before generation.

If only Qwen needs to be installed/repaired, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\setup_qwen3_tts_windows.ps1
```

## Runtime design

Qwen3-TTS is much larger than Kokoro, so ThreadGens loads one persistent model on `127.0.0.1:8765` instead of loading a model per narration clip. `tools/qwen3_tts.py` is now primarily the bootstrap/configuration helper; production Java workers send narration directly to `tools/qwen3_tts_server.py` over persistent HTTP instead of launching a Python client process for every WAV.

The server contains a worker-aware GPU scheduler. Independent video workers can submit narration concurrently. The scheduler waits only a short microbatch window, takes one waiting item from each worker before filling extra batch capacity, and passes the collected texts to one batched `generate_custom_voice` call. This keeps one copy of the 1.7B model resident while allowing several worker narrations to occupy the GPU batch together.

The Windows batch launcher configures the desired Qwen batch size from the effective ThreadGens worker count before starting the video workers. If OP-image generation forces the video pool to one worker, the Qwen scheduler is configured to one worker as well.

If a requested batch is too large for available CUDA memory, the scheduler catches the OOM, clears temporary CUDA allocations, splits the work into smaller groups, and remembers the smaller safe batch size for later requests. A non-OOM batch failure is recursively split so one bad narration does not fail unrelated worker requests.

Normal narration remains one coherent Qwen generation. Text is split only when it exceeds the long-input safety threshold, and ThreadGens does not time-stretch, pitch-shift, or otherwise phase-process Qwen's generated speech.

Ollama remains a separate process. Both can share the same NVIDIA GPU while VRAM is available.

The service log is written to:

```text
output\runtime\qwen3_tts_server.log
```

The health endpoint reports scheduler state including worker target, queue depth, maximum/safe batch size, active batch size, completed requests, OOM backoffs, and average queue/inference timing:

```powershell
Invoke-RestMethod http://127.0.0.1:8765/health
```

## Normal runner

Run:

```bat
run_ai_windows.bat
```

The runner now selects:

```text
--tts qwen3
--voice Ryan
--voice-series Ryan,Aiden
--voice-selection series
```

You can also call the Java pipeline directly:

```powershell
java -cp out redditTxtToImg.CheckedRunner `
  --platform reddit `
  --auto `
  --topic "weird everyday stories" `
  --count 10 `
  --tts qwen3 `
  --tts-command .\.venv-qwen3-tts\Scripts\python.exe `
  --voice Ryan `
  --video --concat-video
```

## Supported Qwen CustomVoice speakers

The model exposes the Qwen CustomVoice speakers. For English narration, `Ryan` and `Aiden` are the recommended native-English voices. Other released speakers can still be selected by name.

## Environment overrides

- `THREADGENS_QWEN3_MODEL` - model id/path. Default: `Qwen/Qwen3-TTS-12Hz-1.7B-CustomVoice`
- `THREADGENS_QWEN3_URL` - service URL. Default: `http://127.0.0.1:8765`
- `THREADGENS_QWEN3_DEVICE` - CUDA device. Default: `cuda:0`
- `THREADGENS_QWEN3_ATTN` - attention implementation. Default: `sdpa` for Windows compatibility
- `THREADGENS_QWEN3_STARTUP_TIMEOUT` - first-load timeout in seconds. Default: `1200`
- `THREADGENS_QWEN3_REQUEST_TIMEOUT` - synthesis/queue request timeout in seconds. Default: `1800`
- `THREADGENS_QWEN3_WORKERS` - scheduler worker target. The Windows batch launcher sets this from the effective worker count
- `THREADGENS_QWEN3_MAX_BATCH` - maximum requested GPU microbatch size. The batch launcher sets this from the effective worker count
- `THREADGENS_QWEN3_BATCH_WINDOW_MS` - maximum time to wait for peer worker requests before dispatch. Windows batch default: `100`
- `THREADGENS_QWEN3_MAX_QUEUE` - bounded pending chunk queue. Windows batch default: two entries per effective worker, minimum four
- `THREADGENS_QWEN3_VERBOSE=1` - enable service/client request logging
- `THREADGENS_PYTORCH_INDEX` - override the CUDA PyTorch wheel index used by setup

## Kokoro fallback

Kokoro and Piper were not removed. Existing commands that explicitly use `--tts kokoro` or `--tts piper` continue through the old backend.
