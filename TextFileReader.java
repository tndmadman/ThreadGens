package redditTxtToImg;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

public class TextFileReader {
	private ArrayList<String> lines;
    public  void readTextFile(String filename) throws IOException {
        this.lines = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new FileReader(filename));
        String line = reader.readLine();
        while (line != null) {
            lines.add(line);
            line = reader.readLine();
        }
        reader.close();
        }
    public String getEntry(int i) {
    	return this.lines.get(i);
    }
    public ArrayList<String> getListOfNames(int i) {
    	return this.lines;
    }
    public int getSize() {
    	return this.lines.size();
    }
}
