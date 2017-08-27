package org.csanchez.jenkins.plugins.kubernetes.property;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jenkins.model.Jenkins;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang.StringUtils;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesFactoryAdapter;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.TokenProducer;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.google.common.collect.Lists.newArrayList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class KubernetesJobCloud extends Cloud {

    public static final int DEFAULT_MAX_REQUESTS_PER_HOST = 32;
    private static final Logger LOGGER = Logger.getLogger(KubernetesJobCloud.class.getName());
    private static final String DEFAULT_ID = "jenkins/slave-default";
    private static final Map<String, String> DEFAULT_POD_LABELS = ImmutableMap.of("jenkins", "slave");
    private static final int DEFAULT_RETENTION_TIMEOUT_MINUTES = 5;
    private String defaultsProviderTemplate;
    private PodTemplate template;
    private String serverUrl;
    @CheckForNull
    private String serverCertificate;
    private boolean skipTlsVerify;
    private String namespace;
    private String jenkinsUrl;
    @CheckForNull
    private String jenkinsTunnel;
    @CheckForNull
    private String credentialsId;
    private int containerCap = Integer.MAX_VALUE;
    private int retentionTimeout = DEFAULT_RETENTION_TIMEOUT_MINUTES;
    private int connectTimeout;
    private int readTimeout;
    private int maxRequestsPerHost;

    @DataBoundConstructor
    public KubernetesJobCloud(String name) {
        super(name);
    }

    public KubernetesJobCloud(String name, PodTemplate template, String serverUrl, String namespace, String jenkinsUrl, String containerCapStr, int connectTimeout, int readTimeout, int retentionTimeout) {
        this(name);
        this.template = template;
        setServerUrl(serverUrl);
        setNamespace(namespace);
        setJenkinsUrl(jenkinsUrl);
        setContainerCapStr(containerCapStr);
        setRetentionTimeout(retentionTimeout);
        setConnectTimeout(connectTimeout);
        setReadTimeout(readTimeout);

    }

    @Override
    public synchronized Collection<PlannedNode> provision(@CheckForNull final Label label, final int excessWorkload) {
        try {
            LOGGER.log(Level.INFO, "Excess workload after pending Spot instances: " + excessWorkload);
            LOGGER.log(Level.INFO, "Template: " + template.getDisplayName());
            if (!addProvisionedSlave(template, label)) {
                return ImmutableList.of();
            }
            return newArrayList(new PlannedNode(template.getDisplayName(), Computer.threadPoolForRemoting.submit(new KubernetesProvisioningCallback(this, template)), 1));
        } catch (KubernetesClientException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectException || cause instanceof UnknownHostException) {
                LOGGER.log(Level.WARNING, "Failed to connect to Kubernetes at {0}: {1}",
                        new String[]{serverUrl, cause.getMessage()});
            } else {
                LOGGER.log(Level.WARNING, "Failed to count the # of live instances on Kubernetes",
                        cause != null ? cause : e);
            }
        } catch (ConnectException e) {
            LOGGER.log(Level.WARNING, "Failed to connect to Kubernetes at {0}", serverUrl);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to count the # of live instances on Kubernetes", e);
        }
        return Collections.emptyList();
    }

    private boolean addProvisionedSlave(@Nonnull PodTemplate template, @CheckForNull Label label) throws Exception {
        if (containerCap == 0) {
            return true;
        }

        KubernetesClient client = connect();
        String templateNamespace = isNotBlank(template.getNamespace()) ? template.getNamespace() : client.getNamespace();
        List<Pod> slaveListItems = client.pods().inNamespace(templateNamespace).withLabels(DEFAULT_POD_LABELS).list().getItems();

        Map<String, String> labelsMap = getLabelsMap(template.getLabelSet());
        List<Pod> namedListItems = client.pods().inNamespace(templateNamespace).withLabels(labelsMap).list().getItems();
        if (nonNull(slaveListItems) && containerCap <= slaveListItems.size()) {
            LOGGER.log(Level.INFO, "Total container cap of {0} reached, not provisioning: {1} running or errored in namespace {2}", new Object[]{containerCap, slaveListItems.size(), client.getNamespace()});
            return false;
        }

        if (nonNull(namedListItems) && nonNull(slaveListItems) && template.getInstanceCap() <= namedListItems.size()) {
            LOGGER.log(Level.INFO, "Template instance cap of {0} reached for template {1}, not provisioning: {2} running or errored in namespace {3} with label {4}", new Object[]{template.getInstanceCap(), template.getName(), slaveListItems.size(), client.getNamespace(), label == null ? "" : label.toString()});
            return false;
        }
        return true;
    }

    @SuppressFBWarnings("all")
    public KubernetesClient connect() throws UnrecoverableKeyException, NoSuchAlgorithmException, KeyStoreException,
            IOException, CertificateEncodingException {
        LOGGER.log(Level.FINE, "Building connection to Kubernetes {0} URL {1}", new String[]{getDisplayName(), serverUrl});
        final KubernetesClient kubernetesClient = new KubernetesFactoryAdapter(serverUrl, namespace, serverCertificate, credentialsId, skipTlsVerify, connectTimeout, readTimeout, maxRequestsPerHost).createClient();
        LOGGER.log(Level.FINE, "Connected to Kubernetes {0} URL {1}", new String[]{getDisplayName(), serverUrl});
        return kubernetesClient;
    }

    private String getIdForLabel(Label label) {
        return isNull(label) ? DEFAULT_ID : "jenkins/" + label.getName();
    }

    Map<String, String> getLabelsMap(Set<LabelAtom> labelSet) {
        ImmutableMap.Builder<String, String> builder = ImmutableMap.<String, String>builder();
        builder.putAll(DEFAULT_POD_LABELS);
        if (!labelSet.isEmpty()) {
            for (LabelAtom label : labelSet) {
                builder.put(getIdForLabel(label), "true");
            }
        }
        return builder.build();
    }

    @Override
    public boolean canProvision(@CheckForNull Label label) {
        return label.matches(template.getLabelSet());
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Kubernetes";
        }

        public FormValidation doTestConnection(@QueryParameter String name, @QueryParameter String serverUrl, @QueryParameter String credentialsId,
                                               @QueryParameter String serverCertificate,
                                               @QueryParameter boolean skipTlsVerify,
                                               @QueryParameter String namespace,
                                               @QueryParameter int connectionTimeout,
                                               @QueryParameter int readTimeout) throws Exception {

            if (StringUtils.isBlank(serverUrl))
                return FormValidation.error("URL is required");
            if (StringUtils.isBlank(name))
                return FormValidation.error("name is required");
            if (StringUtils.isBlank(namespace))
                return FormValidation.error("namespace is required");

            try {
                KubernetesClient client = new KubernetesFactoryAdapter(serverUrl, namespace,
                        Util.fixEmpty(serverCertificate), Util.fixEmpty(credentialsId), skipTlsVerify,
                        connectionTimeout, readTimeout).createClient();

                client.pods().list();
                return FormValidation.ok("Connection successful");
            } catch (KubernetesClientException e) {
                LOGGER.log(Level.FINE, String.format("Error connecting to %s", serverUrl), e);
                return FormValidation.error("Error connecting to %s: %s", serverUrl, e.getCause() == null
                        ? e.getMessage()
                        : String.format("%s: %s", e.getCause().getClass().getName(), e.getCause().getMessage()));
            } catch (Exception e) {
                LOGGER.log(Level.FINE, String.format("Error connecting to %s", serverUrl), e);
                return FormValidation.error("Error connecting to %s: %s", serverUrl, e.getMessage());
            }
        }

        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String serverUrl) {
            return new StandardListBoxModel()
                    .withEmptySelection()
                    .withMatching(
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(TokenProducer.class),
                                    CredentialsMatchers.instanceOf(StandardCertificateCredentials.class)
                            ),
                            CredentialsProvider.lookupCredentials(StandardCredentials.class,
                                    Jenkins.getInstance(),
                                    ACL.SYSTEM,
                                    serverUrl != null ? URIRequirementBuilder.fromUri(serverUrl).build()
                                            : Collections.EMPTY_LIST
                            ));

        }

        public FormValidation doCheckMaxRequestsPerHostStr(@QueryParameter String value) throws IOException, ServletException {
            try {
                Integer.parseInt(value);
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please supply an integer");
            }
        }
    }

    @Override
    public String toString() {
        return String.format("KubernetesJobCloud name: %s serverUrl: %s", name, serverUrl);
    }

    private Object readResolve() {
        if ((serverCertificate != null) && !serverCertificate.trim().startsWith("-----BEGIN CERTIFICATE-----")) {
            serverCertificate = new String(Base64.decodeBase64(serverCertificate.getBytes(UTF_8)), UTF_8);
            LOGGER.log(Level.INFO, "Upgraded Kubernetes server certificate key: {0}",
                    serverCertificate.substring(0, 80));
        }

        if (maxRequestsPerHost == 0) {
            maxRequestsPerHost = DEFAULT_MAX_REQUESTS_PER_HOST;
        }
        return this;
    }

    public int getRetentionTimeout() {
        return retentionTimeout;
    }

    @DataBoundSetter
    public void setRetentionTimeout(int retentionTimeout) {
        this.retentionTimeout = retentionTimeout;
    }

    public String getDefaultsProviderTemplate() {
        return defaultsProviderTemplate;
    }

    @DataBoundSetter
    public void setDefaultsProviderTemplate(String defaultsProviderTemplate) {
        this.defaultsProviderTemplate = defaultsProviderTemplate;
    }

    public PodTemplate getTemplate() {
        return template;
    }

    @DataBoundSetter
    public void setTemplate(@Nonnull PodTemplate template) {
        this.template = template;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    @DataBoundSetter
    public void setServerUrl(@Nonnull String serverUrl) {
        Preconditions.checkArgument(!StringUtils.isBlank(serverUrl));
        this.serverUrl = serverUrl;
    }

    public String getServerCertificate() {
        return serverCertificate;
    }

    @DataBoundSetter
    public void setServerCertificate(String serverCertificate) {
        this.serverCertificate = Util.fixEmpty(serverCertificate);
    }

    public boolean isSkipTlsVerify() {
        return skipTlsVerify;
    }

    @DataBoundSetter
    public void setSkipTlsVerify(boolean skipTlsVerify) {
        this.skipTlsVerify = skipTlsVerify;
    }

    @Nonnull
    public String getNamespace() {
        return namespace;
    }

    @DataBoundSetter
    public void setNamespace(@Nonnull String namespace) {
        Preconditions.checkArgument(!StringUtils.isBlank(namespace));
        this.namespace = namespace;
    }

    public String getJenkinsUrl() {
        return jenkinsUrl;
    }

    @DataBoundSetter
    public void setJenkinsUrl(String jenkinsUrl) {
        this.jenkinsUrl = jenkinsUrl;
    }

    public String getJenkinsTunnel() {
        return jenkinsTunnel;
    }

    @DataBoundSetter
    public void setJenkinsTunnel(String jenkinsTunnel) {
        this.jenkinsTunnel = Util.fixEmpty(jenkinsTunnel);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmpty(credentialsId);
    }

    public int getContainerCap() {
        return containerCap;
    }

    @DataBoundSetter
    public void setContainerCapStr(String containerCapStr) {
        if (containerCapStr.equals("")) {
            this.containerCap = Integer.MAX_VALUE;
        } else {
            this.containerCap = Integer.parseInt(containerCapStr);
        }
    }

    public String getContainerCapStr() {
        if (containerCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(containerCap);
        }
    }

    public int getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    public int getConnectTimeout() {
        return connectTimeout;
    }

    @DataBoundSetter
    public void setMaxRequestsPerHostStr(String maxRequestsPerHostStr) {
        try {
            this.maxRequestsPerHost = Integer.parseInt(maxRequestsPerHostStr);
        } catch (NumberFormatException e) {
            maxRequestsPerHost = DEFAULT_MAX_REQUESTS_PER_HOST;
        }
    }

    public String getMaxRequestsPerHostStr() {
        return String.valueOf(maxRequestsPerHost);
    }

    public void setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

}
