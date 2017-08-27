package org.csanchez.jenkins.plugins.kubernetes.property;

import hudson.model.Label;
import hudson.model.labels.LabelAssignmentAction;
import hudson.model.labels.LabelAtom;
import hudson.model.queue.SubTask;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

public class KubernetesLabelAssignmentAction implements LabelAssignmentAction {

    private String label;

    public KubernetesLabelAssignmentAction(final String label) {
        this.label = label;
    }

    @Override
    public Label getAssignedLabel(@Nonnull final SubTask task) {
        return new LabelAtom(label);
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return null;
    }
}
