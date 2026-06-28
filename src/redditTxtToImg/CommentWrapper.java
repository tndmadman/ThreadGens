package redditTxtToImg;

import java.awt.FontMetrics;
import java.util.ArrayList;
import java.util.List;

public class CommentWrapper {
    private CommentWrapper() {
    }

    public static List<String> wrapComment(String comment, FontMetrics metrics, int maxPixelWidth) {
        List<String> wrappedLines = new ArrayList<>();

        if (comment == null || comment.isBlank()) {
            wrappedLines.add("");
            return wrappedLines;
        }

        StringBuilder currentLine = new StringBuilder();
        String[] words = comment.split("\\s+");

        for (String word : words) {
            String candidate = currentLine.length() == 0 ? word : currentLine + " " + word;

            if (metrics.stringWidth(candidate) <= maxPixelWidth) {
                currentLine.setLength(0);
                currentLine.append(candidate);
                continue;
            }

            if (currentLine.length() > 0) {
                wrappedLines.add(currentLine.toString());
                currentLine.setLength(0);
            }

            if (metrics.stringWidth(word) <= maxPixelWidth) {
                currentLine.append(word);
            } else {
                splitLongWord(word, metrics, maxPixelWidth, wrappedLines, currentLine);
            }
        }

        if (currentLine.length() > 0) {
            wrappedLines.add(currentLine.toString());
        }

        return wrappedLines;
    }

    private static void splitLongWord(String word, FontMetrics metrics, int maxPixelWidth,
                                      List<String> wrappedLines, StringBuilder currentLine) {
        StringBuilder part = new StringBuilder();

        for (char c : word.toCharArray()) {
            String candidate = part.toString() + c;
            if (metrics.stringWidth(candidate) > maxPixelWidth && part.length() > 0) {
                wrappedLines.add(part.toString());
                part.setLength(0);
            }
            part.append(c);
        }

        currentLine.append(part);
    }
}
