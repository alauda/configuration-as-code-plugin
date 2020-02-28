package io.jenkins.plugins.casc.auto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import jdk.javadoc.internal.doclets.toolkit.util.DocFinder.Output;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

/**
 * Apply the patch between two versions of the initial config files
 */
public class PatchConfig {
    private static final Logger LOGGER = Logger.getLogger(CasCBackup.class.getName());

    final static  String DEFAULT_JENKINS_YAML_PATH = "jenkins.yaml";
    final static String cascDirectory = DEFAULT_JENKINS_YAML_PATH + ".d/";
    final static  String cascUserConfigFile = "user.yaml";

    @Initializer(after= InitMilestone.STARTED, fatal=false)
    public static void patchConfig() {
        LOGGER.fine("start to calculate the patch of casc");

        URL newSystemConfig = null;
        File newSystemConfigFile = new File(Jenkins.getInstance().getRootDir(), DEFAULT_JENKINS_YAML_PATH);
        try {
            if (newSystemConfigFile.isFile()) {
                newSystemConfig = newSystemConfigFile.toURI().toURL();
            }
        } catch (MalformedURLException e) {
            LOGGER.severe("error when get new system config file, " + e.getMessage());
        }

        URL webInfo = findConfig("/WEB-INF");
        if (webInfo == null) {
            LOGGER.severe("cannot found directory WEB-INF, exit without do the config patch");
            return;
        }

        File userConfigDir = new File(webInfo.getFile(), cascDirectory);
        if (!userConfigDir.exists()) {
            boolean result = userConfigDir.mkdirs();

            LOGGER.info("create user config dir " + result);
        }

        File systemConfig = new File(webInfo.getFile(), DEFAULT_JENKINS_YAML_PATH);
        File userConfig = new File(userConfigDir, cascUserConfigFile);

        if (newSystemConfig == null) {
            LOGGER.warning("no need to upgrade the configuration of Jenkins due to no new config");
            return;
        }

        try {
            // give systemConfig a real path
            PatchConfig.copyAndDelSrc(newSystemConfig, systemConfig.toURI().toURL());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "error happen when copy the new system config", e);
            return;
        }

        YamlMapper mapper = new YamlMapper();
        try (OutputStream userFileOutput = new FileOutputStream(userConfig)) {
            JsonNode merged = merge(
                mapper.read(userConfig),
                mapper.read(systemConfig)
            );
            mapper.write(new YAMLFactory().createGenerator(userFileOutput), merged);
        } catch (IOException e) {
            e.printStackTrace();
        }

//        JsonNode patch = null;
//        if (userConfig.exists()) {
//            ObjectMapper objectMapper = new ObjectMapper();
//            try (InputStream systemConfigStream = new FileInputStream(systemConfig);
//                InputStream userConfigStream = new FileInputStream(userConfig)) {
//                JsonNode source = objectMapper.readTree(yamlToJson(systemConfigStream));
//                JsonNode target = objectMapper.readTree(yamlToJson(userConfigStream));
//
//                patch = JsonDiff.asJson(source, target);
//            } catch (IOException e) {
//                LOGGER.log(Level.SEVERE, "error happen when calculate the patch", e);
//                return;
//            }
//        }
//
//        if (patch != null) {
//            File userJSONFile = new File(userConfigDir, "user.json");
//
//            try (InputStream newSystemInput = new FileInputStream(systemConfig);
//                 OutputStream userFileOutput = new FileOutputStream(userConfig);
//                 OutputStream patchFileOutput = new FileOutputStream(userJSONFile)){
//                ObjectMapper jsonReader = new ObjectMapper();
//                JsonNode target = JsonPatch.apply(patch, jsonReader.readTree(yamlToJson(newSystemInput)));
//
//                String userYaml = jsonToYaml(new ByteArrayInputStream(target.toString().getBytes(StandardCharsets.UTF_8)));
//
//                userFileOutput.write(userYaml.getBytes(StandardCharsets.UTF_8));
//                patchFileOutput.write(patch.toString().getBytes(StandardCharsets.UTF_8));
//            } catch (IOException e) {
//                LOGGER.log(Level.SEVERE, "error happen when copy the new system config", e);
//            }
//        } else {
//            LOGGER.warning("there's no patch of casc");
//        }
    }

    private static URL findConfig(String path) {
        final ServletContext servletContext = Jenkins.getInstance().servletContext;
        try {
            return servletContext.getResource(path);
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, String.format("error happen when finding path %s", path), e);
        }
        return null;
    }

    private static void copyAndDelSrc(URL src, URL target) throws IOException {
        try {
            PatchConfig.copy(src, target);
        } finally {
            boolean result = new File(src.getFile()).delete();
            LOGGER.fine("src file delete " + result);
        }
    }

    private static void copy(URL src, URL target) throws IOException {
        try (InputStream input = src.openStream();
            OutputStream output = new FileOutputStream(target.getFile())) {
            IOUtils.copy(input, output);
        }
    }

    private static String jsonToYaml(InputStream input) throws IOException {
        ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonReader = new ObjectMapper();

        Object obj = jsonReader.readValue(input, Object.class);

        return yamlReader.writeValueAsString(obj);
    }

    private  static String yamlToJson(InputStream input) throws IOException {
        ObjectMapper yamlReader = new ObjectMapper(new YAMLFactory());
        ObjectMapper jsonReader = new ObjectMapper();

        Object obj = yamlReader.readValue(input, Object.class);

        return jsonReader.writeValueAsString(obj);
    }

    private static JsonNode merge(JsonNode mainNode, JsonNode updateNode) {
        Iterator<String> fieldNames = updateNode.fieldNames();

        while (fieldNames.hasNext()) {
            String updatedFieldName = fieldNames.next();
            JsonNode valueToBeUpdated = mainNode.get(updatedFieldName);
            JsonNode updatedValue = updateNode.get(updatedFieldName);

            if (valueToBeUpdated != null && valueToBeUpdated.isArray() &&
                updatedValue.isArray()) {
                ArrayNode updatedArrayNode = (ArrayNode)updatedValue;
                ArrayNode arrayNodeToBeUpdated = (ArrayNode)valueToBeUpdated;
                for(int i = 0; updatedArrayNode.has(i);++i) {
                    if(arrayNodeToBeUpdated.has(i)) {
                        JsonNode mergedNode = merge(arrayNodeToBeUpdated.get(i), updatedArrayNode.get(i));
                        arrayNodeToBeUpdated.set(i, mergedNode);
                    } else {
                        arrayNodeToBeUpdated.add(updatedArrayNode.get(i));
                    }
                }
                // if the Node is an @ObjectNode
            } else if (valueToBeUpdated != null && valueToBeUpdated.isObject() && updatedValue != null && !updatedValue.isNull()) {
                merge(valueToBeUpdated, updatedValue);
            } else {
                if (updatedValue != null && !updatedValue.isNull()) {
                    ((ObjectNode) mainNode).replace(updatedFieldName, updatedValue);
                }
                else {
                    ((ObjectNode) mainNode).remove(updatedFieldName);
                }
            }
        }
        if (updateNode instanceof TextNode) {
            return updateNode;
        }
        return mainNode;
    }
}