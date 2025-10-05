package com.jpage4500.hubitat.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;

import org.jetbrains.annotations.Nullable;

import javax.swing.*;

import java.awt.*;

public class HubitatInstallDialog extends DialogWrapper {
    private JTextField ipField;
    private JRadioButton appRadio;
    private JRadioButton driverRadio;
    private JPanel panel;

    public HubitatInstallDialog(@Nullable Project project, String initialIp, Boolean initialIsApp) {
        super(project);
        setTitle("Hubitat Install Setup");
        ipField = new JTextField(initialIp != null ? initialIp : "", 16);
        appRadio = new JRadioButton("App");
        driverRadio = new JRadioButton("Device Driver");
        ButtonGroup group = new ButtonGroup();
        group.add(driverRadio);
        group.add(appRadio);
        if (initialIsApp != null) {
            if (initialIsApp) appRadio.setSelected(true);
            else driverRadio.setSelected(true);
        }
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("Hubitat IP Address:"), gbc);
        gbc.gridx = 1;
        panel.add(ipField, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        panel.add(new JLabel("Type:"), gbc);
        gbc.gridx = 1;
        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        radioPanel.add(driverRadio);
        radioPanel.add(Box.createHorizontalStrut(12));
        radioPanel.add(appRadio);
        panel.add(radioPanel, gbc);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return panel;
    }

    public String getIp() {
        return ipField.getText().trim();
    }

    public boolean isApp() {
        return appRadio.isSelected();
    }
}
