# ThreadGens

ThreadGens is a Java tool that reads lines from a text file and creates one vertical 1080x1920 PNG image per line.

The default layout is built for 9:16 short-form video frames. It keeps the card centered with top and bottom safe space so the output fits TikTok, YouTube Shorts, and Reels-style compositions better.

![Sample preview](docs/sample-preview.svg)

## Build

```bash
javac -d out src/redditTxtToImg/*.java
```

Or with Gradle:

```bash
gradle build
```

## Run

```bash
java -cp out redditTxtToImg.RedditScreenshotGenerator data/comments.txt output
```

## GUI

```bash
java -cp out redditTxtToImg.GuiApp
```

The GUI lets you choose an input text file, choose an output folder, and generate images.

## CLI options

```bash
java -cp out redditTxtToImg.RedditScreenshotGenerator data/comments.txt output --count 3 --prefix sample --style light --shuffle --center
```

Options:

- `--count N` limits how many images are generated.
- `--prefix NAME` changes output file names.
- `--style dark|light` chooses a template from `templates/`.
- `--shuffle` shuffles input lines before rendering.
- `--center` centers comments in the vertical card.
- `--top` keeps comments closer to the top of the card.
- `--no-watermark` hides the small watermark.

## Files

- `src/redditTxtToImg/` contains the Java source.
- `data/author_names.txt` contains sample names.
- `data/comments.txt` contains sample input lines.
- `defaults.txt` contains default render settings.
- `templates/` contains simple color templates.
- `assets/` contains optional images.

Generated images are written to the output folder.

## Runnable jar

```bash
gradle jar
java -jar build/libs/ThreadGens-1.0.0.jar data/comments.txt output
```
