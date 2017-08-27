package org.csanchez.jenkins.plugins.kubernetes.property;

import org.apache.commons.lang.RandomStringUtils;

import static org.apache.commons.lang.StringUtils.isEmpty;

public class SlaveNameUtils {

    private static final transient String NAME_FORMAT = "%s-%s";
    private static final String DEFAULT_AGENT_PREFIX = "jenkins-agent";

    public static String getSlaveName(String name) {
        String randString = RandomStringUtils.random(5, "bcdfghjklmnpqrstvwxz0123456789");
        if (isEmpty(name)) {
            return String.format(NAME_FORMAT, DEFAULT_AGENT_PREFIX, randString);
        }
        name = name.replaceAll("[ _]", "-").toLowerCase();
        name = name.substring(0, Math.min(name.length(), 62 - randString.length()));
        return String.format(NAME_FORMAT, name, randString);
    }

}
