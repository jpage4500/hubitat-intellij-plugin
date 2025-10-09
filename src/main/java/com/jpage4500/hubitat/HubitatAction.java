package com.jpage4500.hubitat;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import com.jpage4500.hubitat.models.InstallRequest;
import com.jpage4500.hubitat.models.InstallResult;
import com.jpage4500.hubitat.models.UserDeviceType;
import com.jpage4500.hubitat.settings.HubitatInstallDialog;
import com.jpage4500.hubitat.settings.HubitatSettingsState;
import com.jpage4500.hubitat.utils.ExcludeFromSerialization;
import com.jpage4500.hubitat.utils.GsonHelper;
import com.jpage4500.hubitat.utils.NetworkHelper;
import com.jpage4500.hubitat.utils.TextUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HubitatAction extends AnAction {
    private static final Logger log = LoggerFactory.getLogger(HubitatAction.class);

    private static final String TITLE = "Hubitat Plugin";

    private NetworkHelper networkHelper;

    public HubitatAction() {
        super("Install to Hubitat");
    }

    public static class DriverDetails {
        public String name;
        public String namespace;
        public String hubIp;
        public Boolean isApp;
        public String appId;
        @ExcludeFromSerialization
        public String text;
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        // Get contents of current file in editor
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null) {
            log.error("actionPerformed: No active editor");
            showWarning(project, "No active editor.");
            return;
        }

        DriverDetails details = new DriverDetails();

        // get current editor text
        Document document = editor.getDocument();
        details.text = document.getText();
        VirtualFile file = FileDocumentManager.getInstance().getFile(document);
        String fileName = file != null ? file.getName() : "";
        String filePath = file != null ? file.getPath() : "";

        log.debug("actionPerformed: {}", fileName);

        // check if this looks like a Hubitat app/driver
        if (!TextUtils.containsIgnoreCase(details.text, "definition")) {
            log.error("actionPerformed: invalid app/driver file");
            showWarning(project, "This does not appear to be a Hubitat app or device driver (missing definition).");
            return;
        }

        // definition(name: "File Manager Device", namespace: "jpage4500", author: "Joe Page") {
        details.name = parseValue(details.text, "name");
        details.namespace = parseValue(details.text, "namespace");
        if (TextUtils.isEmptyAny(details.name, details.namespace)) {
            showWarning(project, "This does not appear to be a Hubitat app or device driver (missing name/namespace).");
            return;
        }

        // get hub IP from comments:
        // hub: 192.168.0.200
        details.hubIp = parseValue(details.text, "hub");

        // get type (app or device) from comments:
        // type: device
        details.isApp = isApp(details.text);

        if (details.isApp == null) {
            // guess type based on filename
            if (TextUtils.containsIgnoreCase(fileName, "app")) {
                log.debug("isApp: filename is app: {}", fileName);
                details.isApp = true;
            } else if (TextUtils.containsIgnoreCase(fileName, "driver")) {
                log.debug("isApp: filename is driver: {}", fileName);
                details.isApp = false;
            }
        }

        HubitatSettingsState state = HubitatSettingsState.getInstance();
        if (state != null) {
            // if IP address not specified, use saved IP address
            if (TextUtils.isEmpty(details.hubIp)) {
                details.hubIp = state.hubIp;
                if (!TextUtils.isEmpty(details.hubIp)) log.debug("actionPerformed: cached IP: {}", details.hubIp);
            }

            if (details.isApp == null) {
                // check if we cached this path -> app/driver type
                details.isApp = state.getPathToApp(filePath);
                if (details.isApp != null) log.debug("actionPerformed: cached isApp: {} -> {}", filePath, details.isApp);
            }
        }

        HubitatInstallDialog dialog = new HubitatInstallDialog(project, details.hubIp, details.isApp);
        dialog.setListener((selectedIp, selectedIsApp) -> {
            if (selectedIsApp == null) {
                dialog.addResult("Select an app/driver type to continue");
                log.warn("app/driver not selected ");
                return false;
            } else if (!isValidIp(selectedIp)) {
                dialog.addResult("Invalid IP address");
                log.warn("Invalid IP address: {}", selectedIp);
                return false;
            }
            details.hubIp = selectedIp;
            details.isApp = selectedIsApp;

            if (state != null) {
                // save IP address for future use
                state.hubIp = selectedIp;
                // save path -> app/driver type
                state.setPathToApp(filePath, selectedIsApp);
            }

            String type = (selectedIsApp ? "app" : "driver");
            // get app/driver id from comments:
            // id: 1711
            details.appId = parseValue(details.text, "id");

            // run network requests on background thread
            new Thread(() -> {
                if (TextUtils.isEmpty(details.appId)) {
                    // lookup existing app/driver by name/namespace
                    dialog.addResult("\uD83D\uDD39 Looking up " + type + " ID for \"" + details.name + "\"...");
                    lookupAppId(dialog, details);
                } else {
                    dialog.addResult("\uD83D\uDD39 Updating " + type + " on Hubitat...");
                    updateApp(dialog, details);
                }
            }).start();
            return true;
        });
        dialog.show();

        // TODO: start update automatically
        if (!TextUtils.isEmpty(details.appId)) {
            //dialog.install();
        }
    }

    /**
     * Determine if this is an app or device driver
     *
     * @return true = app, false = device driver, null = unknown/cancel
     */
    private Boolean isApp(String text) {
        String type = parseValue(text, "type");
        if (TextUtils.equalsIgnoreCase(type, "app")) {
            log.debug("isApp: found type: {}", type);
            return true;
        } else if (TextUtils.equalsIgnoreCase(type, "device")) {
            log.debug("isApp: found type: {}", type);
            return false;
        }

        // Driver: Contains a metadata block with definition, and usually declares capability, attribute, and command.
        // App: Contains a definition block (not inside metadata), and often uses app, section, and input for user configuration.
        //   - Apps do not use the capability keyword
        if (TextUtils.containsAny(text, true, "capability", "metadata")) {
            // drivers contain capability/metadata keywords
            log.debug("isApp: contains capability/metadata");
            return false;
        } else if (TextUtils.containsAny(text, true, "definition", "section", "page")) {
            return true;
        }
        // unknown
        return null;
    }

    private boolean installApp(HubitatInstallDialog dialog, DriverDetails details) {
        // TODO: prompt user to confirm install of new app/driver
        // this could be a new app/driver; prompt user to install
//        String driverType = details.isApp ? "app" : "device driver";
//        int rc = Messages.showYesNoDialog(dialog,
//            "Existing " + driverType + " not found.\n\nInstall as new " + driverType + "?",
//            TITLE, Messages.getQuestionIcon());
//        if (rc != Messages.YES) return false;

        String type = details.isApp ? "/app" : "/driver";
        String createUrl = "http://" + details.hubIp + type + "/create";
        if (networkHelper == null) networkHelper = new NetworkHelper();
        networkHelper.getRequest(createUrl, getHeaders(details));

        // install new app/driver
        // POST http://192.168.0.200/driver/saveOrUpdateJson
        // POST http://192.168.0.200/app/saveOrUpdateJson
        String urlStr = "http://" + details.hubIp + type + "/saveOrUpdateJson";

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Content-Type", "application/json");
        headers.put("Host", details.hubIp);
        headers.put("Origin", "http://" + details.hubIp);
        headers.put("Referer", createUrl);
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");

        InstallRequest request = new InstallRequest();
        request.source = details.text;

        NetworkHelper.HttpResponse response = networkHelper.postRequest(urlStr, GsonHelper.toJson(request), headers);
        return handleResult(dialog, response);
    }

    private boolean updateApp(HubitatInstallDialog dialog, DriverDetails details) {
        // POST /device/ideUpdate?id=885 HTTP/1.1
        String type = details.isApp ? "/app" : "/device";
        String urlStr = "http://" + details.hubIp + type + "/ideUpdate?id=" + details.appId;
        if (networkHelper == null) networkHelper = new NetworkHelper();
        Map<String, String> headers = getHeaders(details);
        NetworkHelper.HttpResponse response = networkHelper.postRequest(urlStr, details.text, headers);
        return handleResult(dialog, response);
    }

    private boolean handleResult(HubitatInstallDialog dialog, NetworkHelper.HttpResponse response) {
        if (response.status != 200) {
            dialog.addResult("❌ " + response.body);
            dialog.done();
            return false;
        }
        InstallResult result = GsonHelper.fromJson(response.body, InstallResult.class);
        if (result == null || !result.success) {
            String errorMsg = (result == null) ? "Unknown error" : result.message;
            dialog.addResult("❌ Error: " + errorMsg);
            dialog.done();
            return false;
        }

        dialog.addResult("✅ Success!");
        dialog.done();
        return true;
    }

    /**
     * Lookup app/driver ID by name/namespace
     *
     * @return true if found or not found (but no error), false on error
     */
    private boolean lookupAppId(HubitatInstallDialog dialog, DriverDetails details) {
        // http://192.168.0.200/hub2/userDeviceTypes
        // http://192.168.0.200/hub2/userAppTypes
        String urlStr = "http://" + details.hubIp + "/hub2/" + (details.isApp ? "userAppTypes" : "userDeviceTypes");

        if (networkHelper == null) networkHelper = new NetworkHelper();
        Map<String, String> headers = getHeaders(details);
        NetworkHelper.HttpResponse response = networkHelper.getRequest(urlStr, headers);
        if (response.status != 200) {
            dialog.addResult("❌ " + response.body);
            return false;
        }
        String type = (details.isApp ? "app" : "driver");

        List<UserDeviceType> deviceTypeList = GsonHelper.stringToList(response.body, UserDeviceType.class);
        //     {
        //        "id": 884,
        //        "name": "Dropbox Album",
        //        "namespace": "jpage4500",
        //        "oauth": "enabled",
        //        "lastModified": "2025-06-12T18:39:52+0000",
        //        "usedBy": []
        //    },
        for (UserDeviceType deviceType : deviceTypeList) {
            if (TextUtils.equals(deviceType.name, details.name) &&
                TextUtils.equals(deviceType.namespace, details.namespace)) {
                dialog.addResult("\uD83D\uDD39 Found " + type + " ID: " + deviceType.id);
                log.info("lookupAppId: FOUND: {}", GsonHelper.toJson(deviceType));
                details.appId = String.valueOf(deviceType.id);
                updateApp(dialog, details);
                return true;
            }
        }
        dialog.addResult("❌ \"" + details.name + "\" not found");
        log.error("lookupAppId: NOT_FOUND: results:{}, {}", deviceTypeList.size(), GsonHelper.toJson(details));

        dialog.addResult("\uD83D\uDD39 Installing " + type + " on Hubitat...");
        installApp(dialog, details);
        return true;
    }

    private Map<String, String> getHeaders(DriverDetails details) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "text/plain; charset=ISO-8859-1");
        headers.put("Origin", "http://" + details.hubIp);
        headers.put("Host", details.hubIp + ":8080");
        headers.put("User-Agent", "Apache-HttpClient/4.5.14 (Java/21.0.8)");
        headers.put("Accept-Encoding", "gzip,deflate");
        return headers;
    }

    private String parseValue(String text, String key) {
        // hub: 192.168.0.200
        // type: device
        // id: 1711
        // definition(name: "File Manager Device", namespace: "jpage4500", author: "Joe Page") {
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
        log.debug("parseValue: {} = \"{}\"", key, result);
        return null;
    }

    private void showWarning(Project project, String message) {
        if (ApplicationManager.getApplication().isDispatchThread()) {
            Messages.showWarningDialog(project, message, HubitatAction.TITLE);
        } else {
            ApplicationManager.getApplication().invokeLater(() -> showWarning(project, message));
        }
    }

    private static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        // IPv4 regex
        String ipv4Pattern =
            "^(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\." +
                "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\." +
                "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\." +
                "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$";
        return ip.matches(ipv4Pattern);
    }

}
