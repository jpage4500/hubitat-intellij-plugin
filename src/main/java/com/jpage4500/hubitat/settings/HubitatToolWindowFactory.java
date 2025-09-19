package com.jpage4500.hubitat.settings;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

import javax.swing.*;

import java.awt.*;

public class HubitatToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static JTextArea logArea;

    @Override
    public void createToolWindowContent(Project project, ToolWindow toolWindow) {
        JPanel panel = new JPanel(new BorderLayout());

        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);

        panel.add(scrollPane, BorderLayout.CENTER);

        ContentFactory contentFactory = ContentFactory.getInstance();
        Content content = contentFactory.createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
    }

    /**
     * Append a line to the console
     */
    public static void log(String message) {
        if (logArea != null) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(message + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }
    }
}
