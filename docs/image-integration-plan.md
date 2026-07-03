# Future OP image integration

This branch only reserves the shape for future image support. It does not generate or render OP images yet.

Planned behavior:

- `--image-mode none|local|comfyui` will choose whether an original-post image is used.
- `--op-image PATH` will accept an already-created local image for the original post.
- `--image-dir DIR` will store generated OP images.
- `--image-cache-dir DIR` will store intermediate local image assets.
- `--comfy-url URL` will point at a local ComfyUI server, defaulting to `http://127.0.0.1:8188`.

Implementation notes for later:

- Keep image generation separate from text generation, rendering, TTS, and video stitching.
- Generate or resolve OP image assets before frame rendering starts.
- Do not make Reddit/X renderers call ComfyUI directly.
- Add a small image service/helper that returns a local image path, then let renderers draw that path if present.
- Keep generated images under `output/images/` and transient assets under `output/cache/images/`.
- Keep profile/avatar cache separate under `output/cache/pfp/`.
