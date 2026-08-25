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
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HubitatAction extends AnAction {
    private static final Logger log = LoggerFactory.getLogger(HubitatAction.class);

    private static final String TITLE = "Hubitat Plugin";

    /** longest value parseValue() will accept (name, namespace, ip, id, ..) */
    private static final int MAX_VALUE_LENGTH = 256;

    /** matches "definition(" and "definition (" - the app/driver header block */
    private static final Pattern DEFINITION_PATTERN = Pattern.compile("\\bdefinition\\s*\\(");

    /** driver-only keywords: capability "Switch", metadata { .. } */
    private static final Pattern DRIVER_PATTERN = Pattern.compile("\\bcapability\\s+[\"']|\\bmetadata\\s*\\{");

    /**
     * app-only keywords: page(..), dynamicPage(..), section(..)
     * NOTE: must be anchored - a bare "page" substring matches namespaces like "jpage4500"
     */
    private static final Pattern APP_PATTERN = Pattern.compile("\\b(?:dynamicP|p)age\\s*\\(|\\bsection\\s*[({\"']");

    /** cache of key -> pattern used by parseValue() */
    private static final Map<String, Pattern> keyPatternMap = new ConcurrentHashMap<>();

    public HubitatAction() {
        super("Install to Hubitat");
    }

    public static class DriverDetails {
        public String name;
        public String namespace;
        public String hubIp;
        public Boolean isApp;
        public Integer appId;
        @ExcludeFromSerialization
        public String text;
        // NOTE: not shared between runs - the HUBSESSION cookie is tied to a single hub
        @ExcludeFromSerialization
        public NetworkHelper networkHelper;
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
        // definition(name: "File Manager Device", namespace: "jpage4500", author: "Joe Page") {
        String definition = parseDefinition(details.text);
        if (definition == null) {
            log.error("actionPerformed: invalid app/driver file");
            showWarning(project, "This does not appear to be a Hubitat app or device driver (missing definition).");
            return;
        }

        // NOTE: only look *inside* definition(..) - files often contain unrelated "name:" values
        // eg: @Field static final Map APPS = ["com.netflix.ninja": [name: "Netflix"], ..]
        details.name = parseValue(definition, "name");
        details.namespace = parseValue(definition, "namespace");
        if (TextUtils.isEmptyAny(details.name, details.namespace)) {
            showWarning(project, "This does not appear to be a Hubitat app or device driver (missing name/namespace).");
            return;
        }

        // get hub IP from comments:
        // hub: 192.168.0.200
        details.hubIp = parseComment(details.text, "hub");

        // get type (app or device) from comments:
        // type: device
        details.isApp = isApp(details.text);

        if (details.isApp == null) {
            // guess type based on filename
            // NOTE: check "driver" first - "appliance-driver.groovy" contains both
            if (TextUtils.containsIgnoreCase(fileName, "driver")) {
                log.debug("isApp: filename is driver: {}", fileName);
                details.isApp = false;
            } else if (TextUtils.containsIgnoreCase(fileName, "app")) {
                log.debug("isApp: filename is app: {}", fileName);
                details.isApp = true;
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
                if (details.isApp != null)
                    log.debug("actionPerformed: cached isApp: {} -> {}", filePath, details.isApp);
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
            // new session (cookie store) per install; the hub IP may have changed
            details.networkHelper = new NetworkHelper();

            if (state != null) {
                // save IP address for future use
                state.hubIp = selectedIp;
                // save path -> app/driver type
                state.setPathToApp(filePath, selectedIsApp);
            }

            // get app/driver id from comments:
            // id: 1711
            String idStr = parseComment(details.text, "id");
            if (TextUtils.notEmpty(idStr)) {
                int id = TextUtils.getNumberInt(idStr, 0);
                if (id > 0) details.appId = id;
            }
            log.debug("actionPerformed: GO: {}", GsonHelper.toJson(details));

            // run network requests on background thread
            new Thread(() -> {
                if (details.appId == null || details.appId <= 0) {
                    // lookup existing app/driver by name/namespace
                    log.debug("actionPerformed: looking up ID: {}", GsonHelper.toJson(details));
                    lookupAppId(dialog, details);
                } else {
                    log.debug("actionPerformed: updating: {}", GsonHelper.toJson(details));
                    updateApp(dialog, details);
                }
            }).start();
            return true;
        });
        dialog.show();
    }

    /**
     * Determine if this is an app or device driver
     *
     * @return true = app, false = device driver, null = unknown/cancel
     */
    private Boolean isApp(String text) {
        // look for Hubitat header (optional):
        // hubitat start
        // hub: 192.168.0.200
        // type: app
        // id: 684
        // hubitat end
        String type = parseComment(text, "type");
        if (TextUtils.equalsIgnoreCase(type, "app")) {
            log.debug("isApp: type=app");
            return true;
        } else if (TextUtils.equalsIgnoreCase(type, "device")) {
            log.debug("isApp: type=device");
            return false;
        }

        // Driver: Contains a metadata block with definition, and usually declares capability, attribute, and command.
        // capability "Actuator"
        // App: Contains a definition block (not inside metadata), and often uses app, section, and input for user configuration.
        //   - Apps do not use the capability keyword
        //
        if (DRIVER_PATTERN.matcher(text).find()) {
            // drivers contain capability/metadata keywords
            log.debug("isApp: type=device (capability/metadata)");
            return false;
        } else if (APP_PATTERN.matcher(text).find()) {
            log.debug("isApp: type=app (section/page)");
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
        // NOTE: this GET is what hands us the HUBSESSION cookie used by the POST below
        details.networkHelper.getRequest(createUrl, getHeaders(details));

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

        NetworkHelper.HttpResponse response = details.networkHelper.postRequest(urlStr, GsonHelper.toJson(request), headers);
        return handleResult(dialog, response);
    }

    private boolean updateApp(HubitatInstallDialog dialog, DriverDetails details) {
        String type = details.isApp ? "/app" : "/device";
        dialog.addResult("\uD83D\uDD39 Updating " + typeName(details) + " on Hubitat...");

        // POST /device/ideUpdate?id=885 HTTP/1.1
        String urlStr = "http://" + details.hubIp + type + "/ideUpdate?id=" + details.appId;
        Map<String, String> headers = getHeaders(details);
        NetworkHelper.HttpResponse response = details.networkHelper.postRequest(urlStr, details.text, headers);
        return handleResult(dialog, response);
    }

    private boolean handleResult(HubitatInstallDialog dialog, NetworkHelper.HttpResponse response) {
        if (response.status != 200) {
            showError(dialog, response);
            return false;
        }
        InstallResult result = GsonHelper.fromJson(response.body, InstallResult.class);
        if (result == null || !result.success) {
            String errorMsg = (result == null) ? "Unknown error" : result.message;
            dialog.addResult("❌ Error: " + errorMsg);
            dialog.failed();
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

        String type = typeName(details);
        dialog.addResult("\uD83D\uDD39 Looking up " + type + " ID for \"" + details.name + "\"...");

        Map<String, String> headers = getHeaders(details);
        NetworkHelper.HttpResponse response = details.networkHelper.getRequest(urlStr, headers);
        if (response.status != 200) {
            showError(dialog, response);
            return false;
        }

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
                details.appId = deviceType.id;
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

    private String typeName(DriverDetails details) {
        return details.isApp ? "app" : "driver";
    }

    private void showError(HubitatInstallDialog dialog, NetworkHelper.HttpResponse response) {
        // NOTE: body is null when the hub replies with no content (eg. a 302 redirect to the login page)
        String error = TextUtils.notEmpty(response.body) ? response.body : "HTTP " + response.status;
        dialog.addResult("❌ " + error);
        dialog.failed();
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

    /**
     * Find the contents of the definition(..) block - both apps and drivers have one:
     * <p>
     * metadata { definition(name: "x", namespace: "y") { .. } }   <- driver
     * definition(name: "x", namespace: "y")                       <- app
     *
     * @return text *between* the parenthesis or null if not found
     */
    private String parseDefinition(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = DEFINITION_PATTERN.matcher(text);
        while (matcher.find()) {
            // matcher ends on the '(' itself
            int openIndex = matcher.end() - 1;
            int closeIndex = findClosingParen(text, openIndex);
            if (closeIndex < 0) continue;
            String definition = text.substring(openIndex + 1, closeIndex);
            // skip any definition(..) that doesn't declare a name (eg. commented out sample code)
            if (TextUtils.contains(definition, "name")) return definition;
        }
        log.debug("parseDefinition: not found");
        return null;
    }

    /**
     * @return index of the ')' matching the '(' at openIndex or -1; ignores parenthesis inside quotes
     */
    private static int findClosingParen(String text, int openIndex) {
        int depth = 0;
        char quote = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                // inside a string - only look for the closing quote
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
                continue;
            }
            switch (c) {
                case '\"':
                case '\'':
                    quote = c;
                    break;
                case '(':
                    depth++;
                    break;
                case ')':
                    depth--;
                    if (depth == 0) return i;
                    break;
            }
        }
        return -1;
    }

    /**
     * Look up a value the user added in a comment (hub, type, id):
     * <p>
     * // hubitat start
     * // hub: 192.168.0.200
     * // type: device
     * // id: 1782
     * // hubitat end
     * <p>
     * NOTE: only comments are searched so we never pick up a value from code (eg. "[id: 5]")
     */
    private String parseComment(String text, String key) {
        if (TextUtils.isEmpty(text)) return null;

        // prefer the "hubitat start/end" block when it exists
        int startIndex = TextUtils.indexOf(text, "hubitat start");
        if (startIndex >= 0) {
            int endIndex = text.indexOf("hubitat end", startIndex);
            if (endIndex < 0) endIndex = text.length();
            return parseValue(text.substring(startIndex, endIndex), key);
        }

        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!TextUtils.startsWithAny(trimmed, false, "//", "*", "/*")) continue;
            String value = parseValue(trimmed, key);
            if (value != null) return value;
        }
        log.debug("parseComment: key not found: {}", key);
        return null;
    }

    /**
     * Find "key: value" within the given text; handles quoted and unquoted values
     * <p>
     * name: "File Manager Device",   ->  File Manager Device
     * name: 'go2rtc',                ->  go2rtc
     * // id: 1782                    ->  1782
     *
     * @param text scope to search - NOT the entire file (see parseDefinition/parseComment)
     */
    private String parseValue(String text, String key) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher matcher = keyPattern(key).matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null) continue;
            value = value.trim();
            // remove surrounding quotes
            if (value.length() >= 2 && (value.charAt(0) == '\"' || value.charAt(0) == '\'')) {
                value = value.substring(1, value.length() - 1).trim();
            }
            if (TextUtils.isEmpty(value)) continue;
            if (value.length() > MAX_VALUE_LENGTH) {
                log.error("parseValue: {} exceeded max length: {}", key, value.length());
                return null;
            }
            log.debug("parseValue: {} = \"{}\"", key, value);
            return value;
        }
        log.debug("parseValue: key not found: {}", key);
        return null;
    }

    /**
     * matches: <key>: "value" | 'value' | value
     * NOTE: the lookbehind stops "name" from matching "fileName:" or "displayName:"
     */
    private static Pattern keyPattern(String key) {
        return keyPatternMap.computeIfAbsent(key, k -> Pattern.compile(
                "(?<![A-Za-z0-9_])" + Pattern.quote(k) + "\\s*:[ \\t]*(\"[^\"]*\"|'[^']*'|[^,)\\r\\n]*)"));
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
