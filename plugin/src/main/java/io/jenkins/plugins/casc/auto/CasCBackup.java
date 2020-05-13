package io.jenkins.plugins.casc.auto;

import hudson.Extension;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.model.Saveable;
import hudson.model.listeners.ItemListener;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.casc.ConfigurationAsCode;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;

@Extension(ordinal = Integer.MIN_VALUE)
public class CasCBackup extends SaveableListener {
    private static final Logger LOGGER = Logger.getLogger(CasCBackup.class.getName());

    private static final String DEFAULT_JENKINS_YAML_FILE = "jenkins.yaml";
    private static final String JENKINS_BACKUP_YAML_FILE = "jenkins.backup.yaml";

    private static final boolean enableBackup;
    private static final BlockingQueue<Saveable> queue;

    static {
        enableBackup = "true".equals(System.getenv("CASC_AUTO_BACKUP"));
        queue = new ArrayBlockingQueue<Saveable>(200);
        new Thread(new BackupThread(), "CASC backup thread").start();

        LOGGER.info("CasCBackup is " + (enableBackup ? "enabled" : "disabled"));
    }

    @Override
    public void onChange(Saveable o, XmlFile file) {
        if (!enableBackup) {
            return;
        }

        InitMilestone initLevel = Jenkins.getInstance().getInitLevel();
        if (initLevel != InitMilestone.COMPLETED && initLevel != InitMilestone.JOB_LOADED) {
            return;
        }

        // only take care of the configuration which controlled by casc
        if (!(o instanceof GlobalConfiguration)) {
            return;
        }

        try {
            queue.put(o);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Extension
    public static class BackupThread extends ItemListener implements Runnable {

        @Override
        public void run() {
            while(!Jenkins.getInstance().isTerminating()) {
                try {
                    queue.take(); // don't use the item here, just want to delay the following action
                    if (queue.size() > 0) {
                        Thread.sleep(500);
                        continue;
                    }

                    backup();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onBeforeShutdown() {
            // make sure the queue is empty before Jenkins shutdown
            if (queue.size() > 0) {
                backup();
            }
        }

        private void backup() {
            LOGGER.info("start to backup casc yaml file");

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            File backupFile = new File(Jenkins.getInstance().getRootDir(), JENKINS_BACKUP_YAML_FILE);
            try (OutputStream writer = new FileOutputStream(backupFile)) {
                ConfigurationAsCode.get().export(buf);
                writer.write(buf.toByteArray());

                LOGGER.fine(String.format("backup file was saved, %s", backupFile.getAbsolutePath()));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "error happen when exporting the whole config into a YAML", e);
                return;
            }
            LOGGER.info("done with the backup casc yaml file");
            generatePath();
        }

        private void generatePath() {
            File rootDir = Jenkins.getInstance().getRootDir();
            PatchConfig.patchConfig(new File(rootDir, DEFAULT_JENKINS_YAML_FILE),
                new File(rootDir, JENKINS_BACKUP_YAML_FILE),
                new File(rootDir, "casc_config_auto/" + DEFAULT_JENKINS_YAML_FILE));
        }
    }
}
