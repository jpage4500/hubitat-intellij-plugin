package com.jpage4500.hubitat.settings;

import javax.swing.*;

public class HubitatSettingsComponent {
    private final JPanel panel;
    private final JTextField ipTextField;

    public HubitatSettingsComponent() {
        panel = new JPanel();
        JLabel label = new JLabel("Hubitat IP Address:");
        ipTextField = new JTextField(20);
        panel.add(label);
        panel.add(ipTextField);
    }

    public JPanel getPanel() {
        return panel;
    }

    public String getIpAddress() {
        return ipTextField.getText();
    }

    public void setIpAddress(String ip) {
        ipTextField.setText(ip);
    }
}
