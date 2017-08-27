package org.csanchez.jenkins.plugins.kubernetes.property;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.Queue;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesJobProperty;

import java.util.List;

import static java.util.Objects.isNull;

@Extension
public class KubernetesQueueDecisionHandler extends Queue.QueueDecisionHandler {

    @Override
    public boolean shouldSchedule(final Queue.Task p, final List<Action> actions) {
        AbstractProject<?, ?> project = (AbstractProject<?, ?>) p;
        KubernetesJobProperty kubernetesJobProperty = project.getProperty(KubernetesJobProperty.class);
        return isNull(kubernetesJobProperty) || kubernetesJobProperty.assignLabel(project, actions);
    }
}
