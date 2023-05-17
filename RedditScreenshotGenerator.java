package redditTxtToImg;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

public class RedditScreenshotGenerator {
    private String pfpImg;
    private String userName;
    private String postLocation;
    private String comment;
    private String fileName;
    private int upvotes;
    private int views;

    public RedditScreenshotGenerator(String fileName, String userName, String postLocation, String comment, String pfpImg, int upvotes, int views) {
        this.userName = userName;
        this.postLocation = postLocation;
        this.comment = comment;
        this.pfpImg = pfpImg;
        this.upvotes = upvotes;
        this.views = views;
        this.fileName = fileName;
    }

    public void generateImage() {
        int width = 1080;
        int height = 1920;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Set background color
        g2d.setColor(new Color(26, 26, 27));
        g2d.fillRect(0, 0, width, height);

        // Load the profile picture image from a local file
        File pfpFile = new File("pfp/" + pfpImg);
        ImageIcon pfpIcon = new ImageIcon(pfpFile.getPath());
        Image pfpImage = pfpIcon.getImage();
     // Draw profile picture next to user name
        g2d.drawImage(pfpImage, 30, 30, 60, 60, null);
        
     // Load the Reddit logo image from a local file
        File logoFile = new File("ai_comment.png");
        ImageIcon logoIcon = new ImageIcon(logoFile.getPath());
        Image logoImage = logoIcon.getImage();

        // Draw the Reddit logo image in the top right corner
        int logoWidth = 150 /2;
        int logoHeight = 80;
        int logoX = width - logoWidth - 30;
        int logoY = 30;
        g2d.drawImage(logoImage, logoX, logoY, logoWidth, logoHeight, null);


        // Draw user name and post location
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 30));
        g2d.drawString(userName, 100, 60);
        g2d.setFont(new Font("Arial", Font.PLAIN, 22));
        g2d.drawString(postLocation, 100, 90);

        // Draw comment box
        g2d.setColor(new Color(37, 37, 38));
        g2d.fillRoundRect(30, 120, width - 60, height - 300, 20, 20);

        // Draw comment
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.PLAIN, 26));
        g2d.drawString(comment, 50, 170);

        // Draw upvote and downvote arrows and upvote count
        int arrowX = 50;
        int arrowY = height - 150;
        int arrowWidth = 40;
        int arrowHeight = 40;
        g2d.setColor(new Color(135, 206, 250));
        g2d.fillPolygon(new int[]{arrowX, arrowX + arrowWidth / 2, arrowX + arrowWidth},
                new int[]{arrowY + arrowHeight, arrowY, arrowY + arrowHeight},
                3);
        g2d.setColor(Color.GRAY);
        g2d.fillPolygon(new int[]{arrowX, arrowX + arrowWidth / 2, arrowX + arrowWidth},
                new int[]{arrowY + arrowHeight * 2, arrowY + arrowHeight * 3, arrowY + arrowHeight * 2},
                3);

        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.PLAIN, 22));
        g2d.drawString(String.valueOf(upvotes), arrowX + arrowWidth / 2 - 15, arrowY + arrowHeight * 3 / 2);
        // Draw view icon and view count
        ImageIcon viewIcon = new ImageIcon("/viewed_icon.png");
        Image viewedImage = viewIcon.getImage();
        g2d.drawImage(viewedImage, arrowX + arrowWidth + 40, arrowY + arrowHeight - 10, 30, 30, null);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.PLAIN, 22));
        g2d.drawString(String.valueOf(views) + " views", arrowX + arrowWidth + 80, arrowY + arrowHeight * 3 / 2);

        // Draw timestamp
        g2d.drawImage(viewedImage, arrowX + arrowWidth + 40, arrowY + arrowHeight * 2 + 10, 30, 30, null);
        //g2d.drawString("4 hours ago", arrowX + arrowWidth + 80, arrowY + arrowHeight * 5 / 2);
        
        // Draw random time stamp
        g2d.drawImage(viewedImage, arrowX + arrowWidth + 40, arrowY + arrowHeight * 2 + 10, 30, 30, null);
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.PLAIN, 22));
        String[] timeUnits = {"minute", "hours", "days", "weeks", "months", "years"};
        int randomIndex = new Random().nextInt(timeUnits.length);
        String randomTimeUnit = timeUnits[randomIndex];
        int randomTimeValue = new Random().nextInt(99) + 1; // generate a random number between 1 and 99
        g2d.drawString(randomTimeValue + " " + randomTimeUnit + " ago", arrowX + arrowWidth + 80, arrowY + arrowHeight * 5 / 2);

        
        
        
        g2d.dispose();

        try {
            ImageIO.write(image, "png", new File(fileName + ".png"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
    	RandomProfileName profName = new RandomProfileName();
    	String sProfName = profName.getRandomProfileName();
    	System.out.println(sProfName);
    	
    	TextLoader aLoader = new TextLoader("auther_names.txt");
    	
    	
    	
    	TextFileReader listOfNames = new TextFileReader();
    	
    	String randomName = listOfNames.toString();
		
		int rViews = new Random().nextInt(50000);
		int rLikes = new Random().nextInt(1000);
		RedditScreenshotGenerator generator = new RedditScreenshotGenerator("0first", randomName, "/OverLord", "Today is... i dont know", sProfName, rViews, rLikes);
        generator.generateImage();
        
        generator.postLocation = "postloc";
        generator.comment = "test";
        generator.generateImage();
        
        
        
        //load list of comments
        String commentsFile = readInput();
        List<String> ListOfComments;
		try {
			ListOfComments = TextFileReader.readTextFile(commentsFile);
			System.out.println("comments loaded");
			generator.postLocation = "/OverLord/comment";
        for (int i = 0; i < ListOfComments.size(); i++) {
        	//set the comment from loaded comment list aray location
			generator.comment = ListOfComments.get(i);
			//set random profile picture
			generator.pfpImg = profName.getRandomProfileName();
			//set random profile name
			generator.userName = listOfNames.toString();
			//set random views
			rViews = new Random().nextInt(50000);
			//set random likes
			rLikes = new Random().nextInt(1000);
			generator.fileName = i + "aithread";
			generator.generateImage();
        	
        	
        	
        	
		    System.out.println("Generated: " + i);
        }
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    }
        private  static String readInput() {
            Scanner scanner = new Scanner(System.in);
            System.out.print("Enter input: ");
            String input = scanner.nextLine();
            return input;
        }
    
}
