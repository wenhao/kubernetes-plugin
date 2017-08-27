package org.csanchez.jenkins.plugins.kubernetes.property;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.RetentionStrategy;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.jenkinsci.plugins.durabletask.executors.Messages;
import org.jvnet.localizer.Localizable;
import org.jvnet.localizer.ResourceBundleHolder;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Carlos Sanchez carlos@apache.org
 */
public class KubernetesSlaveBuildWrapper extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(KubernetesSlaveBuildWrapper.class.getName());

    private static final long serialVersionUID = -8642936855413034232L;


    /**
     * The resource bundle reference
     */
    private final static ResourceBundleHolder HOLDER = ResourceBundleHolder.get(Messages.class);

    private final String namespace;
    private KubernetesCloudBuildWrapper cloud;

    public KubernetesSlaveBuildWrapper(PodTemplate template, String nodeDescription, KubernetesCloudBuildWrapper cloud, String labelStr,
                                       RetentionStrategy rs)
            throws Descriptor.FormException, IOException {
        this(template, nodeDescription, labelStr, rs);
        this.cloud = cloud;
    }

    @DataBoundConstructor
    public KubernetesSlaveBuildWrapper(PodTemplate template, String nodeDescription, String labelStr,
                                       RetentionStrategy rs)
            throws Descriptor.FormException, IOException {

        super(template.getName(),
                nodeDescription,
                template.getRemoteFs(),
                1,
                template.getNodeUsageMode() != null ? template.getNodeUsageMode() : Mode.NORMAL,
                labelStr,
                new JNLPLauncher(),
                rs,
                template.getNodeProperties());

        this.namespace = Util.fixEmpty(template.getNamespace());
    }

    public String getCloudName() {
        return this.cloud.name;
    }

    public String getNamespace() {
        return namespace;
    }

    public Cloud getCloud() {
        return this.cloud;
    }


    @Override
    public KubernetesComputerBuildWrapper createComputer() {
        return new KubernetesComputerBuildWrapper(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.log(Level.INFO, "Terminating Kubernetes instance for agent {0}", name);

        Computer computer = toComputer();
        if (computer == null) {
            String msg = String.format("Computer for agent is null: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        if (getCloudName() == null) {
            String msg = String.format("Cloud name is not set for agent, can't terminate: %s", name);
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }

        Cloud cloud = getCloud();
        if (cloud == null) {
            String msg = String.format("Agent cloud no longer exists: %s", getCloudName());
            LOGGER.log(Level.WARNING, msg);
            listener.fatalError(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }
        if (!(cloud instanceof KubernetesCloudBuildWrapper)) {
            String msg = String.format("Agent cloud is not a KubernetesCloudBuildWrapper, something is very wrong: %s",
                    getCloudName());
            LOGGER.log(Level.SEVERE, msg);
            listener.fatalError(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }
        KubernetesClient client;
        try {
            client = ((KubernetesCloudBuildWrapper) cloud).connect();
        } catch (UnrecoverableKeyException | CertificateEncodingException | NoSuchAlgorithmException
                | KeyStoreException e) {
            String msg = String.format("Failed to connect to cloud %s", getCloudName());
            listener.fatalError(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }

        String actualNamespace = getNamespace() == null ? client.getNamespace() : getNamespace();
        try {
            Boolean deleted = client.pods().inNamespace(actualNamespace).withName(name).delete();
            if (!Boolean.TRUE.equals(deleted)) {
                String msg = String.format("Failed to delete pod for agent %s/%s: not found", actualNamespace, name);
                LOGGER.log(Level.WARNING, msg);
                listener.error(msg);
                return;
            }
        } catch (KubernetesClientException e) {
            String msg = String.format("Failed to delete pod for agent %s/%s: %s", actualNamespace, name,
                    e.getMessage());
            LOGGER.log(Level.WARNING, msg, e);
            listener.error(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }

        String msg = String.format("Terminated Kubernetes instance for agent %s/%s", actualNamespace, name);
        LOGGER.log(Level.INFO, msg);
        listener.getLogger().println(msg);
        computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
        LOGGER.log(Level.INFO, "Disconnected computer {0}", name);
    }

    @Override
    public String toString() {
        return String.format("KubernetesSlaveBuildWrapper name: %s", name);
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Kubernetes Slave";
        }

        ;

        @Override
        public boolean isInstantiable() {
            return false;
        }

    }
}
