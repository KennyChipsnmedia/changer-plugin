package io.jenkins.plugins.changer.parameter;

import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;

public class DownstreamPriorityParameterValue extends StringParameterValue {
    public DownstreamPriorityParameterValue(String name, String value) {
        super(name, value);
    }

    public DownstreamPriorityParameterValue(String name, String value, String description) {
        super(name, value, description);
    }
}
