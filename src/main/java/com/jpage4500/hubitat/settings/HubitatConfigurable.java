package com.jpage4500.hubitat.settings;

import com.intellij.openapi.options.Configurable;

import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class HubitatConfigurable implements Configurable {

    private HubitatSettingsComponent component;

    @Nls
    @Override
    public String getDisplayName() {
        return "Hubitat";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        component = new HubitatSettingsComponent();
        HubitatSettingsState state = HubitatSettingsState.getInstance();
        component.setIpAddress(state.hubIp);
        return component.getPanel();
    }

    @Override
    public boolean isModified() {
        HubitatSettingsState state = HubitatSettingsState.getInstance();
        return !component.getIpAddress().equals(state.hubIp);
    }

    @Override
    public void apply() {
        HubitatSettingsState state = HubitatSettingsState.getInstance();
        state.hubIp = component.getIpAddress();
    }

    @Override
    public void reset() {
        HubitatSettingsState state = HubitatSettingsState.getInstance();
        component.setIpAddress(state.hubIp);
    }

    @Override
    public void disposeUIResources() {
        component = null;
    }
}
