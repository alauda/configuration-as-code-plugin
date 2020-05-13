package io.jenkins.plugins.casc.auto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.apache.commons.io.IOUtils;

/**
 * Apply the patch between two versions of the initial config files
 */
public class PatchConfig {
    private static final Logger LOGGER = Logger.getLogger(CasCBackup.class.getName());

    final static  String DEFAULT_JENKINS_YAML_PATH = "jenkins.yaml";

    public static void patchConfig(File systemConfig, File userConfig, File targetConfig) {
        File targetDir = targetConfig.getParentFile();
        if(!targetDir.isDirectory()) {
            LOGGER.info("create target dir" + targetDir.getAbsolutePath() + " result is " + targetDir.mkdirs());
        }

        if (!systemConfig.exists() && !userConfig.exists()) {
            LOGGER.warning("cannot found system config " + systemConfig.getAbsolutePath() +
                "; and user config " + userConfig.getAbsolutePath());
            ExtensionList<GlobalConfiguration> configs = GlobalConfiguration.all();
            if (configs != null && configs.size() > 0) {
                // make sure we can always have a backup config file
                configs.get(0).save();
            } else {
                LOGGER.severe("should not get here, there's no any GlobalConfiguration");
            }
            return;
        }

        if (!systemConfig.exists() && userConfig.exists()) {
            try (InputStream userInput = new FileInputStream(userConfig);
                OutputStream targetOut = new FileOutputStream(targetConfig)) {
                IOUtils.copy(userInput, targetOut);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        if (!userConfig.exists() && systemConfig.exists()) {
            try (InputStream sysInput = new FileInputStream(systemConfig);
                OutputStream targetOut = new FileOutputStream(targetConfig)) {
                IOUtils.copy(sysInput, targetOut);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return;
        }

        YamlMapper mapper = new YamlMapper();
        try {
            JsonNode merged = merge(mapper.read(userConfig), mapper.read(systemConfig));
            if(merged != null && !merged.isNull()) {
                try (OutputStream userFileOutput = new FileOutputStream(targetConfig)) {
                    mapper.write(new YAMLFactory().createGenerator(userFileOutput), merged);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    private static final String DEFAULT_JENKINS_YAML_FILE = "jenkins.yaml";
    private static final String JENKINS_BACKUP_YAML_FILE = "jenkins.backup.yaml";

    @Initializer(after= InitMilestone.STARTED, fatal=false)
    @Deprecated
    public static void patchConfig() {
        File rootDir = Jenkins.getInstance().getRootDir();
        PatchConfig.patchConfig(new File(rootDir, DEFAULT_JENKINS_YAML_FILE),
            new File(rootDir, JENKINS_BACKUP_YAML_FILE),
            new File(rootDir, "casc_config_auto/" + DEFAULT_JENKINS_YAML_FILE));
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

    private static JsonNode merge(JsonNode mainNode, JsonNode updateNode) {
        if (mainNode == null || updateNode == null) {
            return mainNode;
        }
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
