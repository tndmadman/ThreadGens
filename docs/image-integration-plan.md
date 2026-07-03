# OP image generation

This branch starts OP-only image generation for both Reddit and X.

Current behavior:

- Only the original post image (`0<prefix>.png`) gets an OP image overlay.
- Replies stay text-only.
- Existing normal runs stay unchanged because `imageMode=none` by default.
- Use `--image-mode comfyui` to enable local ComfyUI generation.
- Use `--image-mode local --op-image path/to/image.png` to test the overlay with an existing image.

Pipeline:

1. Read the OP post body.
2. Ask Ollama to expand the OP body into a detailed SDXL/RealVisXL image prompt.
3. Send that prompt to local ComfyUI.
4. Generate with `RealVisXL_V5.0_fp32.safetensors`.
5. Save the generated image under `output/images/`.
6. Save the prompt under `output/cache/images/`.
7. Overlay the image onto only the OP screenshot.

Default ComfyUI settings:

- checkpoint: `RealVisXL_V5.0_fp32.safetensors`
- size: `1024x768`
- steps: `30`
- cfg: `5.0`
- sampler: `dpmpp_2m_sde`
- scheduler: `karras`
- ComfyUI URL: `http://127.0.0.1:8188`

Example Reddit run:

```bat
java -cp out redditTxtToImg.CheckedRunner --platform reddit --auto --post-title "What is the creepiest thing you saw at work?" --topic "I was closing alone and the security monitor showed someone standing in the freezer aisle, but the store had been empty for an hour." --count 10 --image-mode comfyui
```

Example X run:

```bat
java -cp out redditTxtToImg.CheckedRunner --platform x --auto --topic "I checked the doorbell camera and saw my own car pull into the driveway even though I was sitting inside it." --count 10 --image-mode comfyui
```

Notes:

- ComfyUI must be running locally before the Java command starts.
- The RealVisXL checkpoint must be available in ComfyUI's checkpoints folder with the exact filename from `imageCheckpoint`.
- The current compositor overlays the generated image onto the finished OP screenshot. A later pass can move this deeper into the renderers if we want text layout to dynamically reflow around the image.
