package org.csanchez.jenkins.plugins.kubernetes;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCertificateCredentials;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.google.common.base.Preconditions;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import hudson.model.Label;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.csanchez.jenkins.plugins.kubernetes.property.KubernetesJobCloud;
import org.csanchez.jenkins.plugins.kubernetes.property.KubernetesLabelAssignmentAction;
import org.csanchez.jenkins.plugins.kubernetes.property.SlaveNameUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.fixEmpty;
import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.isBlank;

public class KubernetesJobProperty extends JobProperty<AbstractProject<?, ?>> {

    public static final int DEFAULT_MAX_REQUESTS_PER_HOST = 32;
    private static final Logger LOGGER = Logger.getLogger(KubernetesJobProperty.class.getName());
    private static final String PROPERTYNAME = "kubernetes_label_assignment";
    private static final int DEFAULT_RETENTION_TIMEOUT_MINUTES = 5;
    private String name;
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
    public KubernetesJobProperty(String name) {
        this.name = name;
    }

    public KubernetesJobProperty(@NonNull KubernetesJobProperty source) {
        this.name = source.name;
        this.defaultsProviderTemplate = source.defaultsProviderTemplate;
        this.template = source.template;
        this.serverUrl = source.serverUrl;
        this.skipTlsVerify = source.skipTlsVerify;
        this.namespace = source.namespace;
        this.jenkinsUrl = source.jenkinsUrl;
        this.credentialsId = source.credentialsId;
        this.containerCap = source.containerCap;
        this.retentionTimeout = source.retentionTimeout;
        this.connectTimeout = source.connectTimeout;
    }

    public boolean assignLabel(final AbstractProject<?, ?> project, final List<Action> actions) {
        String slaveName = SlaveNameUtils.getSlaveName(template.getName());
        template.setName(slaveName);
        KubernetesJobCloud kubernetesJobCloud = new KubernetesJobCloud(name, template, serverUrl, namespace, jenkinsUrl, containerCap + "", connectTimeout, readTimeout, retentionTimeout);
        kubernetesJobCloud.provision(Label.get(template.getLabel()), 1);
        actions.add(0, new KubernetesLabelAssignmentAction(slaveName));
        return true;
    }

    @Extension
    public static class DescriptorImpl extends JobPropertyDescriptor {

        @Override
        public String getDisplayName() {
            return "Kubernetes Build Image";
        }

        @Override
        public JobProperty<?> newInstance(final StaplerRequest req, final JSONObject formData) throws FormException {
            super.newInstance(req, formData);
            if (formData.isNullObject()) {
                return null;
            }

            JSONObject form = formData.getJSONObject(PROPERTYNAME);
            if (form == null || form.isNullObject()) {
                return null;
            }

            @SuppressWarnings("unchecked")
            Class<? extends KubernetesJobProperty> clazz = (Class<? extends KubernetesJobProperty>) getClass().getEnclosingClass();
            return req.bindJSON(clazz, form);
        }

        @SuppressWarnings("all")
        public FormValidation doTestConnection(@QueryParameter String name, @QueryParameter String serverUrl,
                                               @QueryParameter String credentialsId,
                                               @QueryParameter String serverCertificate,
                                               @QueryParameter boolean skipTlsVerify,
                                               @QueryParameter String namespace,
                                               @QueryParameter int connectionTimeout,
                                               @QueryParameter int readTimeout) throws Exception {

            if (isBlank(serverUrl)) {
                return FormValidation.error("URL is required");
            }
            if (isBlank(name)) {
                return FormValidation.error("name is required");
            }
            if (isBlank(namespace)) {
                return FormValidation.error("namespace is required");
            }

            try {
                KubernetesClient client = new KubernetesFactoryAdapter(serverUrl, namespace, fixEmpty(serverCertificate), fixEmpty(credentialsId), skipTlsVerify, connectionTimeout, readTimeout).createClient();
                client.pods().list();
                return FormValidation.ok("Connection successful");
            } catch (KubernetesClientException e) {
                LOGGER.log(Level.FINE, format("Error connecting to %s", serverUrl), e);
                return FormValidation.error("Error connecting to %s: %s", serverUrl, e.getCause() == null
                        ? e.getMessage()
                        : format("%s: %s", e.getCause().getClass().getName(), e.getCause().getMessage()));
            } catch (Exception e) {
                LOGGER.log(Level.FINE, format("Error connecting to %s", serverUrl), e);
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
        return format("KubernetesJobProperty name: %s serverUrl: %s", name, serverUrl);
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
        Preconditions.checkArgument(!isBlank(serverUrl));
        this.serverUrl = serverUrl;
    }

    public String getServerCertificate() {
        return serverCertificate;
    }

    @DataBoundSetter
    public void setServerCertificate(String serverCertificate) {
        this.serverCertificate = fixEmpty(serverCertificate);
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
        Preconditions.checkArgument(!isBlank(namespace));
        this.namespace = namespace;
    }

    public String getName() {
        return name;
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
        this.jenkinsTunnel = fixEmpty(jenkinsTunnel);
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = fixEmpty(credentialsId);
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
