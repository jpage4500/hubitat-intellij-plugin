package com.jpage4500.hubitat.settings;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

@State(
    name = "HubitatSettingsState",
    storages = @Storage("HubitatPlugin.xml")
)
public class HubitatSettingsState implements PersistentStateComponent<HubitatSettingsState> {
    // values to be persisted
    public String hubIp = "";
    public Map<String, Boolean> pathToAppMap;

    public static HubitatSettingsState getInstance() {
        return com.intellij.openapi.application.ApplicationManager.getApplication().getService(HubitatSettingsState.class);
    }

    @Nullable
    @Override
    public HubitatSettingsState getState() {
        return this;
    }

    @Override
    public void loadState(HubitatSettingsState state) {
        this.hubIp = state.hubIp;
        this.pathToAppMap = state.pathToAppMap;
    }

    public Boolean getPathToApp(String appName) {
        if (pathToAppMap == null) return null;
        return pathToAppMap.get(appName);
    }

    public void setPathToApp(String path, Boolean isApp) {
        if (pathToAppMap == null) pathToAppMap = new HashMap<>();
        pathToAppMap.put(path, isApp);
    }

}
