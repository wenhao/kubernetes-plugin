package org.csanchez.jenkins.plugins.kubernetes.property;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Queue;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesProperty;

import java.util.List;

@Extension
public class KubernetesQueueDecisionHandler extends Queue.QueueDecisionHandler {
    @Override
    public boolean shouldSchedule(final Queue.Task p, final List<Action> actions) {
        AbstractProject<?, ?> project = (AbstractProject<?, ?>) p;
        KubernetesProperty kubernetesProperty = project.getProperty(KubernetesProperty.class);
        return kubernetesProperty == null || kubernetesProperty.assignLabel(project, actions);
    }
}
