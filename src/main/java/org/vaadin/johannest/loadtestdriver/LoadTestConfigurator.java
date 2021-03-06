package org.vaadin.johannest.loadtestdriver;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Scanner;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import elemental.json.Json;
import elemental.json.JsonArray;
import elemental.json.JsonObject;

public class LoadTestConfigurator {

    public static final String V7_GRID_DATACOMM = "com.vaadin.shared.data.DataProviderRpc";
    public static final String V8_GRID_DATACOMM = "com.vaadin.shared.data.DataCommunicatorClientRpc";

    private static final String[] requestTypes = { "\"click\"", "\"disableOnClick\"", "\"setText\"", "[\"text\"",
            "\"select\"", "[\"selected\"", "\"requestRows\"", "[\"collapse\"", "[\"requestChildTree\"",
            "[\"scrollTop\"", "[\"scrollLeft\"", "[\"positionx\"", "[\"positiony\"", "[\"requestChildTree\"",
            "[\"filter\"", "[\"page\"", "\"setChecked\"" };

    private static final String[] matchingProperties = { "id", "caption", "inputPrompt", "placeholder", "styles",
            "resources", "primaryStyleName" };

    private final LoadTestParameters loadTestParameters;

    private final Set<String> mappedConnectorTypeIds = new HashSet<>();
    private final Map<String, Integer> typeIdToCountMap = new HashMap<>();
    private final Map<String, String> connectorIdToTypeIdMap = new HashMap<>();
    private final Map<String, String> connectorIdToParentIdMap = new HashMap<>();

    private final Map<String, String> connectorIdToMatchingPropertyKeyMap = new HashMap<>();
    private final Map<String, String> connectorIdToMatchingPropertyValueMap = new HashMap<>();
    private final Map<String, List<String>> connectorIdToRequestFileNames = new HashMap<>();
    private final Set<String> htmlRequestConnectors = new HashSet<>();
    private final Set<String> requiredConnectorIds = new HashSet<>();

    private int gridCounter;
    private int currentGridIndex = 0;

    private String uiInitRequestFileName;
    private String resourcesPath;
    private String tempFilePath;
    private String className;

    private Properties props;
    private List<String> lines;
    private List<String> connectorIdExtractors;

    public LoadTestConfigurator(LoadTestParameters loadTestParameters) {
        this.loadTestParameters = loadTestParameters;
        loadPropertiesFile();
    }

    public void setResourcesPath(String resourcesPath) {
        this.resourcesPath = resourcesPath;
    }

    public void setTempFilePath(String tempFilePath) {
        this.tempFilePath = tempFilePath;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public void configureTestFile() {
        configureTestFile(true);
    }

    public String configureTestFile(boolean saveResults) {
        System.out.println("### configureTestFile, save=" + saveResults);
        final String fileName = tempFilePath + "/" + className + ".scala";
        Logger.getLogger(LoadTestConfigurator.class.getName()).info("Configuring test file: " + fileName);
        try {
            final File file = new File(fileName);
            final FileReader fr = new FileReader(file);
            final BufferedReader br = new BufferedReader(fr);

            lines = new ArrayList<>();
            connectorIdExtractors = new ArrayList<>();

            readScalaScriptAndDoInitialRefactoring(br, saveResults);

            addRegexExtractChecks();
            addRegexExtractDefinitions();
            addAdditionalImports();

            if (saveResults) {
                final FileWriter fw = new FileWriter(file);
                final BufferedWriter bw = new BufferedWriter(fw);
                for (final String s : lines) {
                    bw.write(s + "\n");
                }

                bw.flush();
                bw.close();
            } else {
                StringBuilder sb = new StringBuilder();
                for (final String s : lines) {
                    sb.append(s).append("\n");
                }
                return sb.toString();
            }
        } catch (final FileNotFoundException e) {
            Logger.getLogger(LoadTestConfigurator.class.getName()).severe("Failed to found file: " + fileName);
            e.printStackTrace();
        } catch (final IOException e) {
            Logger.getLogger(LoadTestConfigurator.class.getName()).severe("Failed to access file: " + fileName);
            e.printStackTrace();
        } catch (final Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private void readScalaScriptAndDoInitialRefactoring(BufferedReader br, boolean saveResults) throws IOException {
        boolean syncIdsInitialized = false;
        String line;
        String newLine;

        while ((line = br.readLine()) != null) {
            newLine = line;

            if (newLine.contains("val scn")) {
                insertHelperMethods(lines);
            }

            if (newLine.matches(".*[\\?&]{1}v-[0-9]{12,15}.*")) {
                lines.add(newLine);
                handleInitializationRequest(br, lines, newLine);
                continue;
            }

            if (!syncIdsInitialized) {
                syncIdsInitialized = initializeSyncAndClientIds(newLine, lines);
            }

            if (newLine.contains(".check(bodyBytes.is(")) {
                lines.add("\t\t\t)");
                continue;
            }

            if (newLine.contains(".exec(http(")) {
                if (loadTestParameters.pausesEnabled()) {
                    lines.add("\t\t.pause(" + loadTestParameters.getMinPause() + ", " +
                            loadTestParameters.getMaxPause() + ")");
                }
            }

            newLine = requestBodyTreatments(newLine, saveResults);

            if (newLine.contains("atOnceUsers")) {
                newLine = newLine.replaceFirst("inject\\(atOnceUsers\\(1\\)\\)",
                        "inject(rampUsers(" + loadTestParameters.getConcurrentUsers() + ") over (" +
                                loadTestParameters.getRampUpTime() + " seconds))");
            }

            lines.add(newLine);
        }
        br.close();
    }

    private void addRegexExtractChecks() {
        for (int i = 0; i < lines.size(); i++) {
            final String aline = lines.get(i);

            if (aline.contains(".post(") && aline.contains("/UIDL/?v-uiId=")) {
                lines.add(i + 2, "\t\t\t.check(syncIdExtract).check(clientIdExtract)");
            }
            for (Map.Entry<String, List<String>> entry : connectorIdToRequestFileNames.entrySet()) {
                if (containsStringInAListOfStrings(aline, entry.getValue()) ||
                        (uiInitRequestFileName != null && aline.contains("check(xsrfTokenExtract)") &&
                                 containsStringInAListOfStrings(uiInitRequestFileName, entry.getValue()))) {
                    String requiredConnectorId = entry.getKey();

                    if (requiredConnectorIds.contains(requiredConnectorId)) {
                        if (connectorIdToMatchingPropertyKeyMap.containsKey(requiredConnectorId)) {
                            String regexExtractor = createExtractorRegex(requiredConnectorId);
                            if (!connectorIdExtractors.contains(regexExtractor)) {
                                regexExtractor = escapeCurlyBraces(regexExtractor);
                                // no need to add duplicate extractor
                                connectorIdExtractors.add(regexExtractor);
                                lines.add(i, "\t\t\t.check(extract_" + requiredConnectorId + "_Id)");
                                ++i;
                            }
                        } else if (typeIdToCountMap.get(requiredConnectorId) == 1) {
                            // one of kind, thus safe to use following regexp
                            String regexExtractor = props
                                    .getProperty("connectorid_extractor_regex_typemap_template");
                            if (htmlRequestConnectors.contains(requiredConnectorId)) {
                                regexExtractor = props
                                        .getProperty("connectorid_extractor_regex_typemap_template_escaped");
                            }
                            regexExtractor = regexExtractor.replace("_XXX_", "_" + requiredConnectorId + "_");
                            regexExtractor = regexExtractor.replace("_YYY_", connectorIdToTypeIdMap.get(requiredConnectorId));
                            regexExtractor = escapeCurlyBraces(regexExtractor);
                            connectorIdExtractors.add(regexExtractor);
                            lines.add(i, "\t\t\t.check(extract_" + requiredConnectorId + "_Id)");
                            ++i;
                        }
                    }
                }
            }
        }
    }

    private void addRegexExtractDefinitions() {
        for (int i = 0; i < lines.size(); i++) {
            final String aline = lines.get(i);
            if (aline.contains("val scn = scenario")) {
                for (String extractor : connectorIdExtractors) {
                    lines.add(i - 1, extractor);
                }
                lines.add(i - 1, "\n");
                break;
            }
        }
    }

    private String escapeCurlyBraces(String regexExtractor) {
        // TODO: this is rather ugly hack to escape curly braces
        System.out.println(regexExtractor);
        regexExtractor = regexExtractor.replaceAll(":\\{", (":\\\\{"));
        System.out.println(regexExtractor);
        if (regexExtractor.contains("}}")) {
            regexExtractor = regexExtractor.replaceAll("([^0-9\\\\])\\}\\}", "$1\\\\}\\\\}");
            System.out.println(regexExtractor);
        } else {
            regexExtractor = regexExtractor.replaceAll("([^0-9\\\\])\\}", "$1\\\\}");
            System.out.println(regexExtractor);
        }
        return regexExtractor;
    }

    private boolean containsStringInAListOfStrings(String firstString, List<String> listOfStrings) {
        for (String str : listOfStrings) {
            if (firstString.contains(str)) {
                return true;
            }
        }
        return false;
    }

    String createExtractorRegex(String requiredConnectorId) {
        final String propertyKey = connectorIdToMatchingPropertyKeyMap.get(requiredConnectorId);
        final String propertyValue = connectorIdToMatchingPropertyValueMap.get(requiredConnectorId);

        if (propertyKey.equals(V7_GRID_DATACOMM)) {
            String regexExtractor = props.getProperty("grid_id_extractor_regex_v7_template");
            if (htmlRequestConnectors.contains(requiredConnectorId)) {
                regexExtractor = props.getProperty("grid_id_extractor_regex_v7_template_escaped");
            }
            regexExtractor = regexExtractor.replace("_XXX_", "_" + requiredConnectorId + "_");
            ++currentGridIndex;
            return regexExtractor;
        }
        if (propertyKey.equals(V8_GRID_DATACOMM)) {
            String regexExtractor = props.getProperty("grid_id_extractor_regex_v8_template");
            if (htmlRequestConnectors.contains(requiredConnectorId)) {
                regexExtractor = props.getProperty("grid_id_extractor_regex_v8_template_escaped");
            }
            regexExtractor = regexExtractor.replace("_XXX_", "_" + requiredConnectorId + "_");
            ++currentGridIndex;
            return regexExtractor;
        }

        String regexExtractor = props.getProperty("connectorid_extractor_regex_template");
        if (htmlRequestConnectors.contains(requiredConnectorId)) {
            regexExtractor = props.getProperty("connectorid_extractor_regex_template_escaped");
        }
        regexExtractor = regexExtractor.replace("_XXX_", "_" + requiredConnectorId + "_");
        regexExtractor = regexExtractor.replace("_YYY_", propertyKey);
        if (propertyKey.equalsIgnoreCase("resources")) {
            regexExtractor = regexExtractor.replace("\"_ZZZ_", escapePropertyValue(propertyValue));
        } else {
            regexExtractor = regexExtractor.replace("_ZZZ_", escapePropertyValue(propertyValue));
        }
        return regexExtractor;
    }

    private String escapePropertyValue(String propertyValue) {
        char[] specialChars = new char[]{'\\','.','[',']','{','}','(',')','*','+','-','?','^','$','|'};
        String result = propertyValue;
        for (char specialChar : specialChars) {
            result = result.replace(Character.toString(specialChar), "\\"+specialChar);
        }
        return result;
    }

    private void addAdditionalImports() {
        lines.add(0, "import io.gatling.core.body.ElFileBody");
    }

    private String requestBodyTreatments(String newLine, boolean saveResults) throws IOException {
        if (newLine.contains("RawFileBody")) {
            newLine = replaceWithELFileBody(newLine, saveResults);
            Logger.getLogger(LoadTestConfigurator.class.getName()).info(newLine);
        }
        return newLine;
    }

    private boolean initializeSyncAndClientIds(String newLine, final List<String> lines) {
        if (newLine.contains(".exec(http(")) {
            lines.add("\t\t.exec(initSyncAndClientIds)");
            return true;
        }
        return false;
    }

    private void handleInitializationRequest(BufferedReader br, List<String> lines, String newLine) throws IOException {
        while (newLine != null && !newLine.matches(".*body\\(RawFileBody.*")) {
            newLine = br.readLine();
        }

        final String fileName = getRequestFileName(newLine);

        if (fileName != null) {
            uiInitRequestFileName = fileName;
            Logger.getLogger(LoadTestConfigurator.class.getName()).info(fileName);
            String responseBody = readRequestResponseFileContent(fileName.replaceFirst("request", "response"));
            readConnectorMap(responseBody, fileName);

            final String requesBody = readRequestResponseFileContent(fileName);
            final String[] requestParameters = requesBody.split("&");
            for (final String requestParam : requestParameters) {
                final String[] keyValuePair = requestParam.split("=");
                if (keyValuePair[0].equals("v-loc")) {
                    keyValuePair[1] = keyValuePair[1].replaceAll("%3A", ":");
                    keyValuePair[1] = keyValuePair[1].replaceAll("%2F", "/");
                }
                final String formattedParameterLine = String
                        .format("\t\t\t.formParam(\"%s\", \"%s\")", keyValuePair[0], keyValuePair[1]);
                lines.add(formattedParameterLine);
            }
        }
        lines.add("\t\t\t.check(xsrfTokenExtract)");
    }

    private String replaceWithELFileBody(String newLine, boolean saveRequest) throws IOException {
        final String fileName = getRequestFileName(newLine);
        if (fileName != null) {
            Logger.getLogger(LoadTestConfigurator.class.getName()).info(fileName);

            String requestBody = readRequestResponseFileContent(fileName);
            String responseBody = readRequestResponseFileContent(fileName.replaceFirst("request", "response"));
            readConnectorMap(responseBody, fileName);
            requestBody = doRequestBodyTreatments(requestBody);

            if (saveRequest) {
                saveRequestFile(resourcesPath + "/bodies/" + fileName, requestBody);
            } else {
                Logger.getLogger(LoadTestConfigurator.class.getName())
                        .info("--- New RequestBody " + "---\n" + requestBody + "\n-----------------------");
            }
            newLine = newLine.replaceFirst("RawFileBody", "ElFileBody");
        }
        return newLine;
    }

    String doRequestBodyTreatments(String requestBody) {
        for (String requestType : requestTypes) {
            final int requestTypeIndex = requestBody.indexOf(requestType);
            if (requestTypeIndex > 0) {
                final String prefixToRequestType = requestBody.substring(0, requestTypeIndex - 1);
                final int idStartIndex = prefixToRequestType.lastIndexOf("[");
                final String substringFromId = requestBody.substring(idStartIndex, requestTypeIndex - 1);
                Scanner in = new Scanner(substringFromId).useDelimiter("[^0-9]+");
                String connectorId = String.valueOf(in.nextInt());
                if (connectorIdToMatchingPropertyKeyMap.containsKey(connectorId) ||
                        (connectorIdToTypeIdMap.get(connectorId) != null && typeIdToCountMap.get(connectorIdToTypeIdMap.get(connectorId)) == 1)) {
                    String idName = "_" + connectorId + "_Id";
                    requestBody = requestBody.replace("\"" + connectorId + "\"", "\"${" + idName + "}\"");
                    requiredConnectorIds.add(connectorId);
                } else {
                    Logger.getLogger(LoadTestConfigurator.class.getName())
                            .info(" ####### No mapping found for connector id " + connectorId + " at request: " + requestBody);
                }
            }
        }

        requestBody = requestBody.replaceFirst("syncId\":[0-9]+", Matcher.quoteReplacement("syncId\":${syncId}"));
        requestBody = requestBody.replaceFirst("clientId\":[0-9]+", Matcher.quoteReplacement("clientId\":${clientId}"));
        requestBody = requestBody.replaceFirst("csrfToken\":\"[a-z0-9\\-]+\"", Matcher.quoteReplacement("csrfToken\":\"${seckey}\""));
        return requestBody;
    }

    private void saveRequestFile(String fileName, String requesBody) throws IOException {
        final File requestFile = new File(fileName);
        final FileWriter requestWriter = new FileWriter(requestFile, false);
        requestWriter.write(requesBody);
        requestWriter.close();
    }

    private String getRequestFileName(String line) {
        final Pattern pattern = Pattern.compile("\"(.*?)\"");
        final Matcher matcher = pattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String readRequestResponseFileContent(final String fileName) {
        return readFileContent(resourcesPath + "/bodies/" + fileName);
    }

    private String readFileContent(String filename) {
        String content = "";
        try (Scanner scanner = new Scanner(new File(filename))) {
            content = scanner.useDelimiter("\\Z").next();
        } catch (final Exception e) {
            Logger.getLogger(LoadTestConfigurator.class.getName()).severe("Failed to read request");
            e.printStackTrace();
        }
        return content;
    }

    private void insertHelperMethods(List<String> lines) {
        lines.add(props.getProperty("sync_and_client_id_init"));
        lines.add("\n");
        lines.add(props.getProperty("sync_id_extract"));
        lines.add(props.getProperty("client_id_extract"));
        lines.add(props.getProperty("xsrf_token_extract"));
        lines.add("\n");
    }

    void readConnectorMap(String responseFileContent, String filename) {
        String responseJson = "";
        boolean htmlRequest = false;
        if (!responseFileContent.startsWith("<!doctype html>")) {
            try {
                responseJson = responseFileContent.replace("for(;;);", "");
                if (responseJson.contains("Vaadin-Security-Key")) {
                    responseJson = responseJson.replaceAll("<span class=.{1,50}</span>", "<span></span>");

                    // get rid of escaped quotes
                    responseJson = responseJson.replace("\\\"", "\"");
                    // get rid of inline json
                    responseJson = responseJson.replaceAll("},\"[0-9]+\":.\"json\":.*?\"}(,\"\\d+?\":\\{)", "}$1");
                    // get rid of grid data
                    responseJson = responseJson.replaceAll(
                                 "DataCommunicatorClientRpc\",\"setData\",\\[.*?]], \"",
                            "DataCommunicatorClientRpc\",\"setData\"]], \"");
                    //responseJson = responseJson.replace("\\\\\"", "\"");
                    htmlRequest = true;
                }
                responseJson = responseJson.replace("uidl\":\"{", Matcher.quoteReplacement("uidl\":{"));
                responseJson = responseJson.replace("]}\"}", Matcher.quoteReplacement("]}}"));
                if (responseJson.startsWith("[")) {
                    responseJson = responseJson.substring(1, responseJson.length() - 1);
                }
                JsonObject jsonObject = Json.parse(responseJson);
                if (!jsonObject.hasKey("state") && jsonObject.hasKey("uidl")) {
                    jsonObject = jsonObject.getObject("uidl");
                }

                final JsonObject initialState = jsonObject.getObject("state");
                final String[] connectorIdsInState = initialState.keys();
                final Deque<String> connectorIdStack = new ArrayDeque<>();
                final Map<String, List<JsonObject>> connectorIdToStatesMap = new HashMap<>();
                final Set<String> usedMatchinPropertyKeyVals = new HashSet<>();

                for (String connectorId : connectorIdsInState) {
                    connectorIdStack.push(connectorId);
                }
                while (!connectorIdStack.isEmpty()) {
                    String connectorId = connectorIdStack.pop();
                    connectorIdToStatesMap.computeIfAbsent(connectorId, k -> new ArrayList<>());
                    final List<JsonObject> states = connectorIdToStatesMap.get(connectorId);
                    JsonObject currentState = null;
                    if (states.isEmpty()) {
                        states.add(initialState);
                    }
                    currentState = states.get(0);

                    JsonObject connectorState = currentState.getObject(connectorId);

                    if (connectorState.hasKey("childData")) {
                        final JsonObject childConnectorState = connectorState.getObject("childData");
                        final String[] childKeys = childConnectorState.keys();
                        for (String childKey : childKeys) {
                            connectorIdStack.push(childKey);
                            connectorIdToStatesMap.computeIfAbsent(childKey, k -> new ArrayList<>());
                            final List<JsonObject> childStates = connectorIdToStatesMap.get(childKey);
                            childStates.add(childConnectorState);
                        }
                    }

                    for (JsonObject state : states) {
                        connectorState = state.getObject(connectorId);
                        for (String matchingProperty : matchingProperties) {
                            if (connectorState.hasKey(matchingProperty) && connectorIdToMatchingPropertyKeyMap.get(connectorId) == null) {
                                connectorIdToMatchingPropertyKeyMap.put(connectorId, matchingProperty);

                                String propertyValue;
                                try {
                                    propertyValue = connectorState.getString(matchingProperty);
                                } catch (Exception e) {
                                    try {
                                        propertyValue = connectorState.getArray(matchingProperty).toJson();
                                    } catch (Exception e1) {
                                        try {
                                            propertyValue = connectorState.getObject(matchingProperty).toJson();
                                        } catch (Exception e2) {
                                            continue;
                                        }
                                    }
                                }
                                if (!propertyValue.isEmpty() && !usedMatchinPropertyKeyVals.contains(matchingProperty + ":" + propertyValue)) {
                                    usedMatchinPropertyKeyVals.add(matchingProperty + ":" + propertyValue);
                                    connectorIdToMatchingPropertyValueMap.put(connectorId, propertyValue);
                                    connectorIdToRequestFileNames.computeIfAbsent(connectorId, k -> new ArrayList<>());
                                    connectorIdToRequestFileNames.get(connectorId).add(filename);
                                    if (htmlRequest) {
                                        htmlRequestConnectors.add(connectorId);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }

                if (jsonObject.hasKey("rpc")) {
                    final JsonArray rpcArray = jsonObject.get("rpc");
                    for (int i = 0; i < rpcArray.length(); i++) {
                        try {
                            final JsonArray subArray = rpcArray.getArray(i);
                            if (subArray.length() > 2) {
                                final String connectorId = subArray.getString(0);
                                final String className = subArray.getString(1);
                                if (className.equals(V7_GRID_DATACOMM) || className.equals(V8_GRID_DATACOMM)) {
                                    if (!connectorIdToMatchingPropertyKeyMap.containsKey(connectorId)) {
                                        connectorIdToMatchingPropertyKeyMap.put(connectorId, className);
                                        connectorIdToMatchingPropertyValueMap.put(connectorId, className);
                                        connectorIdToRequestFileNames.computeIfAbsent(connectorId, k -> new ArrayList<>());
                                        connectorIdToRequestFileNames.get(connectorId).add(filename);
                                        if (htmlRequest) {
                                            htmlRequestConnectors.add(connectorId);
                                        }
                                        ++gridCounter;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            // ignore
                        }
                    }
                }

                if (jsonObject.hasKey("types")) {
                    final JsonObject types = jsonObject.getObject("types");
                    String[] connectorIds = types.keys();
                    for (String connectorId : connectorIds) {
                        final String typeId = types.getString(connectorId);
                        if (!mappedConnectorTypeIds.contains(connectorId)) {
                            mappedConnectorTypeIds.add(connectorId);
                            connectorIdToTypeIdMap.put(connectorId, typeId);
                            typeIdToCountMap.merge(typeId, 1, (a, b) -> a + b);
                        }
                    }
                }

                if (jsonObject.hasKey("hierarchy")) {
                    final JsonObject hierarchy = jsonObject.getObject("hierarchy");
                    String[] connectorIds = hierarchy.keys();
                    for (String connectorId : connectorIds) {
                        final JsonArray childConnectorsIds = hierarchy.getArray(connectorId);
                        for (int i = 0; i < childConnectorsIds.length(); i++) {
                            connectorIdToParentIdMap.put(childConnectorsIds.getString(i), connectorId);
                        }
                    }
                }

            } catch (Exception e) {
                Logger.getLogger(LoadTestConfigurator.class.getName()).severe(responseJson);
                Logger.getLogger(LoadTestConfigurator.class.getName()).severe("Failed to parse response json " + responseJson);
            }
        }
    }

    Map<String, String> getConnectorIdToMatchingPropertyKeyMap() {
        return connectorIdToMatchingPropertyKeyMap;
    }

    Map<String, String> getConnectorIdToMatchingPropertyValueMap() {
        return connectorIdToMatchingPropertyValueMap;
    }

    Map<String, List<String>> getConnectorIdToRequestFileNames() {
        return connectorIdToRequestFileNames;
    }

    private void loadPropertiesFile() {
        props = new Properties();
        try {
            InputStream inputStream = LoadTestConfigurator.class.getClassLoader().getResourceAsStream(("loadtestdriver.properties"));
            props.load(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}