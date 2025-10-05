package com.jpage4500.hubitat.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.jpage4500.hubitat.models.InstallResult;
import com.jpage4500.hubitat.models.UserDeviceType;
import com.jpage4500.hubitat.settings.HubitatSettingsState;
import com.jpage4500.hubitat.utils.GsonHelper;
import com.jpage4500.hubitat.utils.NetworkUtils;
import com.jpage4500.hubitat.utils.TextUtils;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class HubitatAction extends AnAction {
    private static final Logger log = LoggerFactory.getLogger(HubitatAction.class);

    private static final String TITLE = "Hubitat Plugin";

    public HubitatAction() {
        super("Install to Hubitat");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            Messages.showWarningDialog(project, "No active project", TITLE);
            return;
        }

        // Get contents of current file in editor
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            log.debug("actionPerformed: No active editor");
            Messages.showWarningDialog(project, "No active editor.", TITLE);
            return;
        }

        // get current editor text
        Document document = editor.getDocument();
        String text = document.getText();

        log.debug("actionPerformed: file length:" + text.length());

        // check if this looks like a Hubitat app/driver
        if (!TextUtils.containsIgnoreCase(text, "definition")) {
            log.debug("actionPerformed: invalid app/driver file");
            Messages.showWarningDialog(project, "This does not appear to be a Hubitat app or device driver.", TITLE);
            return;
        }

        // get hub IP, app ID, type (app or device)
        String hubIp = getHubIp(project, text);
        if (TextUtils.isEmpty(hubIp)) return;

        Boolean isApp = isApp(project, text, document);
        if (isApp == null) return;

        ProgressManager.getInstance().run(
            new Task.Modal(project, "Install to Hubitat", false) {
                @Override
                public void run(ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText("Preparing install/update...");

                    // check if app id is listed in comments
                    // id: 1711
                    String appId = parseValue(text, "id");
                    if (TextUtils.isEmpty(appId)) {
                        // lookup existing app/driver by name/namespace
                        appId = lookupAppId(hubIp, isApp, text);
                        if (TextUtils.startsWith(appId, "error")) {
                            Messages.showErrorDialog(project, appId, TITLE);
                        }
                    }
                    // if app id found, update; else install as a new app/driver
                    if (!TextUtils.isEmpty(appId)) {
                        indicator.setText("Updating app/device on Hubitat...");
                        updateApp(project, hubIp, isApp, appId, text);
                    } else {
                        indicator.setText("Installing new app/device on Hubitat...");
                        installApp(project, hubIp, isApp, text);
                    }
                }
            }
        );
    }

    /**
     * Determine if this is an app or device driver
     *
     * @return true = app, false = device driver, null = unknown/cancel
     */
    private Boolean isApp(Project project, String text, Document document) {
        String type = parseValue(text, "type");
        if (TextUtils.equalsIgnoreCase(type, "app")) {
            log.debug("isApp: found type: " + type);
            return true;
        } else if (TextUtils.equalsIgnoreCase(type, "device")) {
            log.debug("isApp: found type: " + type);
            return false;
        }

        // guess type based on filename
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        String fileName = file != null ? file.getName() : "";
        String filePath = file != null ? file.getPath() : "";
        if (TextUtils.containsIgnoreCase(fileName, "app")) {
            log.debug("isApp: filename is app: " + fileName);
            return true;
        } else if (TextUtils.containsIgnoreCase(fileName, "driver")) {
            log.debug("isApp: filename is driver: " + fileName);
            return false;
        }

        // check saved settings
        HubitatSettingsState state = HubitatSettingsState.getInstance();
        if (state != null) {
            Boolean isApp = state.getPathToApp(filePath);
            if (isApp != null) {
                log.debug("isApp: using cached isApp: " + isApp + " for file: " + filePath);
                return isApp;
            }
        }

        // prompt user for app/driver
        int rc = Messages.showYesNoCancelDialog(project,
            "Is this a Hubit app?\n\nSelect 'Yes' for app, 'No' for device driver.",
            TITLE, Messages.getQuestionIcon());
        if (rc == Messages.CANCEL) return null;
        boolean isApp = rc == Messages.YES;
        log.debug("isApp: user selected isApp: " + isApp + " for file: " + filePath);

        // persist this filename -> app type mapping
        if (state != null) {
            state.setPathToApp(filePath, isApp);
        }
        return isApp;
    }

    private String getHubIp(Project project, String text) {
        // hubitat start
        // hub: 192.168.0.200
        // type: device
        // id: 1711
        // hubitat end
        String hubIp = parseValue(text, "hub");
        if (TextUtils.isEmpty(hubIp)) {
            // use IP from settings
            HubitatSettingsState state = HubitatSettingsState.getInstance();
            if (state == null) return null;
            else if (TextUtils.isEmpty(state.hubitatIpAddress)) {
                // Prompt user for IP if not set
                hubIp = Messages.showInputDialog(project,
                    "Enter Hubitat IP Address:",
                    "Hubitat Setup",
                    Messages.getQuestionIcon()
                );
                if (TextUtils.isEmpty(hubIp)) return null;
                log.debug("getHubIp: user input hub IP: " + hubIp);
                // save for future
                state.hubitatIpAddress = hubIp;
            } else {
                hubIp = state.hubitatIpAddress;
            }
        } else {
            log.debug("getHubIp: found hub IP: " + hubIp);
        }
        return hubIp;
    }

    private boolean installApp(Project project, String hubIp, Boolean isApp, String text) {
        // this could be a new app/driver; prompt user to install
        String driverType = isApp ? "app" : "device driver";
        int rc = Messages.showYesNoDialog(project,
            "Existing " + driverType + " not found.\n\nInstall as new " + driverType + "?",
            TITLE, Messages.getQuestionIcon());
        if (rc != Messages.YES) return false;

        // install new app/driver
        // POST http://192.168.0.200/driver/saveOrUpdateJson
        // POST http://192.168.0.200/app/saveOrUpdateJson
        String urlStr = "http://" + hubIp + (isApp ? "/app" : "/device") + "/saveOrUpdateJson";
        Pair<Integer, String> resultPair = NetworkUtils.postRequest(urlStr, text);
        String desc = "New " + driverType + "\nIP: " + hubIp;
        return handleResult(project, resultPair, desc);
    }

    private boolean updateApp(Project project, String hubIp, Boolean isApp, String appId, String text) {
        // POST /app/ideUpdate?id=885 HTTP/1.1
        // Content-Length: 4946
        // Content-Type: text/plain; charset=ISO-8859-1
        // Host: 192.168.0.200:8080
        // Connection: Keep-Alive
        // User-Agent: Apache-HttpClient/4.5.14 (Java/21.0.7)
        // Accept-Encoding: gzip,deflate

        // POST /device/ideUpdate?id=885 HTTP/1.1
        String urlStr = "http://" + hubIp + (isApp ? "/app" : "/device") + "/ideUpdate?id=" + appId;
        Pair<Integer, String> resultPair = NetworkUtils.postRequest(urlStr, text);
        String desc = "Update " + (isApp ? "app" : "device driver") + "\nIP: " + hubIp + "\nApp ID: " + appId;
        return handleResult(project, resultPair, desc);
    }

    private boolean handleResult(Project project, Pair<Integer, String> resultPair, String desc) {
        if (resultPair == null) {
            Messages.showErrorDialog(project, "No response from hub.", TITLE);
            return false;
        }
        int status = resultPair.getLeft();
        String message = resultPair.getRight();
        // {"success":true,"message":null}
        InstallResult result = GsonHelper.fromJson(message, InstallResult.class);
        if (result == null || !result.success) {
            String errorMsg = (result == null) ? "Unknown error" : result.message;
            Messages.showErrorDialog(project,
                "Error response from hub: " + status + "\n\n" + errorMsg + "\n\n-- details --\n" + desc,
                TITLE
            );
            return false;
        }

        Messages.showInfoMessage(project, "Success!\n\n-- details --\n" + desc, TITLE);
        return true;
    }

    private String lookupAppId(String hubIp, Boolean isApp, String text) {
        // definition(name: "File Manager Device", namespace: "jpage4500", author: "Joe Page") {
        String name = parseValue(text, "name");
        String namespace = parseValue(text, "namespace");
        if (TextUtils.isEmptyAny(name, namespace)) return "error: name or namespace not found!";

        // http://192.168.0.200/hub2/userDeviceTypes
        // http://192.168.0.200/hub2/userAppTypes
        String urlStr = "http://" + hubIp + "/hub2/" + (isApp ? "userAppTypes" : "userDeviceTypes");

        String data = NetworkUtils.getRequest(urlStr);
        if (data == null) return "error: no response from hub";
        List<UserDeviceType> deviceTypeList = GsonHelper.stringToList(data, UserDeviceType.class);
        //     {
        //        "id": 884,
        //        "name": "Dropbox Album",
        //        "namespace": "jpage4500",
        //        "oauth": "enabled",
        //        "lastModified": "2025-06-12T18:39:52+0000",
        //        "usedBy": []
        //    },
        for (UserDeviceType deviceType : deviceTypeList) {
            if (TextUtils.equals(deviceType.name, name) &&
                TextUtils.equals(deviceType.namespace, namespace)) {
                log.debug("lookupAppId: FOUND: " + GsonHelper.toJson(deviceType));
                return String.valueOf(deviceType.id);
            }
        }
        log.error("lookupAppId: NOT_FOUND: results:" + deviceTypeList.size() + ", isApp: " + isApp + ", name: " + name + ", namespace: " + namespace);
        return null;
    }

    private String parseValue(String text, String key) {
        // hub: 192.168.0.200
        // type: device
        // id: 1711
        //
        //     definition(name: "File Manager Device", namespace: "jpage4500", author: "Joe Page") {
        //
        // definition(
        //    name: "File Manager Album",
        //    namespace: "jpage4500",
        //    oauth: true,
        //    iconUrl: '',
        int index = text.indexOf(key + ":");
        if (index < 0) return null;
        int start = index + key.length() + 1;
        StringBuilder result = new StringBuilder();
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\"':
                case '\'':
                    continue;
                case '\n':
                case ',':
                case ')':
                    // remove spaces from beginning/end
                    String resultStr = result.toString().trim();
                    log.debug("parseValue: " + key + " = \"" + resultStr + "\"");
                    return resultStr;
                default:
                    result.append(c);
                    if (result.length() > 256) {
                        log.error("parseValue: " + key + " exceeded max length");
                        break;
                    }
            }
        }
        log.debug("parseValue: " + key + " = \"" + result + "\"");
        return null;
    }
}
