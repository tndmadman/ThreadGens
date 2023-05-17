package redditTxtToImg;
import java.util.ArrayList;
import java.util.List;

public class CommentWrapper {
    private String comment;
    private int pixelCount;

    public CommentWrapper(String comment, int pixelCount) {
        this.comment = comment;
        this.pixelCount = pixelCount;
    }

    public List<String> wrapComment() {
        // Create a new list to store the wrapped lines of the comment
        List<String> wrappedCommentLines = new ArrayList<>();

        // StringBuilder to build the current line
        StringBuilder currentLine = new StringBuilder();

        // Counter for tracking the current pixel count
        int currentPixelCount = 0;

        // Split the comment into an array of words
        String[] words = comment.split(" ");

        // Iterate over each word in the comment
        for (String word : words) {
            int wordLength = word.length();

            // Check if adding the current word exceeds the pixel count
            if (currentPixelCount + wordLength > pixelCount) {
                // Add the current line to the list and start a new line
                wrappedCommentLines.add(currentLine.toString().trim());
                currentLine = new StringBuilder();
                currentPixelCount = 0;
            }

            // Append the current word and space to the current line
            currentLine.append(word).append(" ");

            // Update the current pixel count, accounting for the added space
            currentPixelCount += wordLength + 1;
        }

        // Add the last line (may be incomplete) to the list
        wrappedCommentLines.add(currentLine.toString().trim());

        // Return the list of wrapped comment lines
        return wrappedCommentLines;
    }
}
