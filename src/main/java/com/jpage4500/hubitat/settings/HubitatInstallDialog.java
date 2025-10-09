package com.jpage4500.hubitat.settings;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.jpage4500.hubitat.utils.TextUtils;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

public class HubitatInstallDialog extends DialogWrapper {
    private JTextField ipField;
    private JRadioButton appRadio;
    private JRadioButton driverRadio;
    private JPanel panel;
    private JTextArea resultsArea;
    private JScrollPane resultsScroll;
    private InstallListener listener;

    public interface InstallListener {
        boolean onInstall(String selectedIp, Boolean selectedIsApp);
    }

    public void setListener(InstallListener listener) {
        this.listener = listener;
    }

    public HubitatInstallDialog(@Nullable Project project, String ip, Boolean isApp) {
        super(project);
        setTitle("Install to Hubitat");
        ipField = new JTextField(ip != null ? ip : "", 16);
        appRadio = new JRadioButton("App");
        driverRadio = new JRadioButton("Device Driver");
        ButtonGroup group = new ButtonGroup();
        group.add(driverRadio);
        group.add(appRadio);
        if (isApp != null) {
            if (isApp) appRadio.setSelected(true);
            else driverRadio.setSelected(true);
        }
        panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.NONE;

        // Create details panel with titled border
        JPanel detailsPanel = new JPanel(new GridBagLayout());
        javax.swing.border.TitledBorder detailsBorder = BorderFactory.createTitledBorder("Details");
        detailsBorder.setTitleFont(detailsBorder.getTitleFont().deriveFont(Font.BOLD));
        detailsPanel.setBorder(detailsBorder);
        GridBagConstraints dgbc = new GridBagConstraints();
        dgbc.insets = new Insets(4, 4, 4, 4);
        dgbc.gridx = 0;
        dgbc.gridy = 0;
        dgbc.anchor = GridBagConstraints.WEST;
        dgbc.fill = GridBagConstraints.NONE;
        detailsPanel.add(new JLabel("Hubitat IP Address:"), dgbc);
        dgbc.gridx = 1;
        dgbc.weightx = 1.0;
        dgbc.fill = GridBagConstraints.HORIZONTAL;
        detailsPanel.add(ipField, dgbc);
        dgbc.weightx = 0;
        dgbc.gridx = 0;
        dgbc.gridy = 1;
        dgbc.fill = GridBagConstraints.NONE;
        detailsPanel.add(new JLabel("Type:"), dgbc);
        dgbc.gridx = 1;
        dgbc.fill = GridBagConstraints.HORIZONTAL;
        JPanel radioPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        radioPanel.add(driverRadio);
        radioPanel.add(Box.createHorizontalStrut(12));
        radioPanel.add(appRadio);
        detailsPanel.add(radioPanel, dgbc);
        // Add detailsPanel to main panel
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        panel.add(detailsPanel, gbc);
        resultsArea = new JTextArea(4, 32);
        resultsArea.setEditable(false);
        resultsArea.setLineWrap(false);
        resultsArea.setWrapStyleWord(true);
        resultsArea.setMinimumSize(new Dimension(200, resultsArea.getFontMetrics(resultsArea.getFont()).getHeight() * 3));
        resultsScroll = new JScrollPane(resultsArea);
        javax.swing.border.TitledBorder resultsBorder = BorderFactory.createTitledBorder("Results");
        resultsBorder.setTitleFont(resultsBorder.getTitleFont().deriveFont(Font.BOLD));
        resultsScroll.setBorder(resultsBorder);
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(resultsScroll, gbc);
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        gbc.weighty = 0;
        gbc.fill = GridBagConstraints.NONE;

        setOKButtonText("Install");

        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        panel.setPreferredSize(new Dimension(450, 300));
        return panel;
    }

    public String getIp() {
        return ipField.getText().trim();
    }

    public Boolean isApp() {
        if (appRadio.isSelected()) return true;
        else if (driverRadio.isSelected()) return false;
        else return null;
    }

    public void setResult(String text) {
        resultsArea.setText(text);
    }

    public void addResult(String text) {
        String results = resultsArea.getText();
        if (!TextUtils.isEmpty(results)) {
            results += "\n";
        }
        results += text;
        resultsArea.setText(results);
    }

    public void install() {
        doOKAction();
    }

    public void done() {
        setOKButtonText("Close");
        setOKActionEnabled(true);
        JButton cancelButton = getButton(getCancelAction());
        if (cancelButton != null) {
            cancelButton.setVisible(false);
        }
        listener = null;
    }

    @Override
    protected void doOKAction() {
        if (listener != null) {
            boolean isOk = listener.onInstall(getIp(), isApp());
            if (isOk) {
                // disable OK button
                setOKActionEnabled(false);
            }
        } else {
            // close dialog
            super.doOKAction();
        }
    }
}
