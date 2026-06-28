# Optional image assets

The generator now runs even when these files are missing. If you add them, they will be used automatically:

- `assets/ai_comment.png` - logo drawn in the upper-right corner.
- `assets/viewed_icon.png` - view icon near the view count.
- `assets/pfp/*.png`, `*.jpg`, `*.jpeg`, or `*.gif` - random profile pictures.

The current code draws fallback icons and profile circles when these images are not present, so a clean clone can still compile and generate PNG output.
