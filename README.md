# ThreadGens

Java tool that reads lines from a text file and creates one vertical PNG image per line.

## Build

javac -d out src/redditTxtToImg/*.java

## Run

java -cp out redditTxtToImg.RedditScreenshotGenerator data/comments.txt output

## Files

- src/redditTxtToImg/ contains the Java source.
- data/author_names.txt contains sample names.
- data/comments.txt contains sample input lines.
- assets/ contains optional images.

Generated images are written to the output folder.
