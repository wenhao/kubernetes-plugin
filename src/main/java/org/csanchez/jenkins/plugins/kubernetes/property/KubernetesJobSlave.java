package org.csanchez.jenkins.plugins.kubernetes.property;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor.FormException;
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
import java.util.logging.Logger;

import static hudson.Util.fixEmpty;
import static java.lang.Boolean.TRUE;
import static java.util.Objects.isNull;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

public class KubernetesJobSlave extends AbstractCloudSlave {

    private static final long serialVersionUID = -8642936855413034232L;
    private static final Logger LOGGER = Logger.getLogger(KubernetesJobSlave.class.getName());
    private final static ResourceBundleHolder HOLDER = ResourceBundleHolder.get(Messages.class);
    private final String namespace;
    private KubernetesJobCloud kubernetesJobCloud;

    public KubernetesJobSlave(PodTemplate template, String slaveName, KubernetesJobCloud kubernetesJobCloud, String labelStr, RetentionStrategy retentionStrategy) throws FormException, IOException {
        this(template, slaveName, labelStr, retentionStrategy);
        this.kubernetesJobCloud = kubernetesJobCloud;
    }

    @DataBoundConstructor
    public KubernetesJobSlave(PodTemplate template, String slaveName, String labelStr, RetentionStrategy retentionStrategy) throws FormException, IOException {
        super(slaveName, slaveName, template.getRemoteFs(), 1, template.getNodeUsageMode() != null ? template.getNodeUsageMode() : Mode.NORMAL, labelStr, new JNLPLauncher(), retentionStrategy, template.getNodeProperties());
        this.namespace = fixEmpty(template.getNamespace());
    }

    @Override
    public KubernetesJobComputer createComputer() {
        return new KubernetesJobComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.log(INFO, "Terminating Kubernetes instance for agent {0}", name);

        Computer computer = toComputer();
        if (isNull(computer)) {
            String msg = String.format("Computer for agent is null: %s", name);
            LOGGER.log(SEVERE, msg);
            listener.fatalError(msg);
            return;
        }

        if (isNull(kubernetesJobCloud.name)) {
            String msg = String.format("Cloud name is not set for agent, can't terminate: %s", name);
            LOGGER.log(SEVERE, msg);
            listener.fatalError(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }

        KubernetesClient client;
        try {
            client = kubernetesJobCloud.connect();
        } catch (Exception e) {
            String msg = String.format("Failed to connect to kubernetesJobCloud %s", kubernetesJobCloud.name);
            listener.fatalError(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }

        String actualNamespace = isNull(namespace) ? client.getNamespace() : namespace;
        try {
            Boolean deleted = client.pods().inNamespace(actualNamespace).withName(name).delete();
            if (!TRUE.equals(deleted)) {
                String msg = String.format("Failed to delete pod for agent %s/%s: not found", actualNamespace, name);
                LOGGER.log(WARNING, msg);
                listener.error(msg);
                return;
            }
        } catch (KubernetesClientException e) {
            String msg = String.format("Failed to delete pod for agent %s/%s: %s", actualNamespace, name, e.getMessage());
            LOGGER.log(WARNING, msg, e);
            listener.error(msg);
            computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
            return;
        }

        String msg = String.format("Terminated Kubernetes instance for agent %s/%s", actualNamespace, name);
        LOGGER.log(INFO, msg);
        listener.getLogger().println(msg);
        computer.disconnect(OfflineCause.create(new Localizable(HOLDER, "offline")));
        LOGGER.log(INFO, "Disconnected computer {0}", name);
    }

    @Override
    public String toString() {
        return String.format("KubernetesJobSlave name: %s", name);
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Kubernetes Slave";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }

    }
}
