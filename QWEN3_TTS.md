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

Qwen3-TTS is much larger than Kokoro, so ThreadGens does not load it once per narration clip. `tools/qwen3_tts.py` automatically starts `tools/qwen3_tts_server.py` on `127.0.0.1:8765`. The server loads Qwen once, keeps it resident on the GPU, and serializes synthesis requests from ThreadGens workers.

Ollama remains a separate process. Both can share the same NVIDIA GPU while VRAM is available.

The service log is written to:

```text
output\runtime\qwen3_tts_server.log
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
- `THREADGENS_QWEN3_REQUEST_TIMEOUT` - synthesis request timeout in seconds. Default: `900`
- `THREADGENS_QWEN3_VERBOSE=1` - enable service/client request logging
- `THREADGENS_PYTORCH_INDEX` - override the CUDA PyTorch wheel index used by setup

## Kokoro fallback

Kokoro and Piper were not removed. Existing commands that explicitly use `--tts kokoro` or `--tts piper` continue through the old backend.
