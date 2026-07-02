package redditTxtToImg;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public class GuiApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                open();
            }
        });
    }

    public static void open() {
        JFrame frame = new JFrame("ThreadGens");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(620, 220);

        JTextField inputField = new JTextField("data/comments.txt");
        JTextField outputField = new JTextField("output");
        JLabel status = new JLabel("Ready");

        JButton inputButton = new JButton("Choose input");
        inputButton.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                inputField.setText(chooser.getSelectedFile().getPath());
            }
        });

        JButton outputButton = new JButton("Choose output");
        outputButton.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                outputField.setText(chooser.getSelectedFile().getPath());
            }
        });

        JButton runButton = new JButton("Generate");
        runButton.addActionListener(event -> {
            runButton.setEnabled(false);
            status.setText("Generating...");
            new Thread(() -> {
                try {
                    CheckedRunner.runOrThrow(new String[]{inputField.getText(), outputField.getText()});
                    setStatus(status, runButton, "Done: " + outputField.getText());
                } catch (Exception ex) {
                    setStatus(status, runButton, "Failed: " + ex.getMessage());
                }
            }, "threadgens-gui-runner").start();
        });

        JPanel form = new JPanel(new GridLayout(3, 2, 8, 8));
        form.add(new JLabel("Input file"));
        form.add(inputField);
        form.add(inputButton);
        form.add(outputButton);
        form.add(runButton);
        form.add(status);

        frame.add(form, BorderLayout.CENTER);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void setStatus(JLabel status, JButton runButton, String message) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message);
            runButton.setEnabled(true);
        });
    }
}
