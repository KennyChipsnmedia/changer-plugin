package io.jenkins.plugins.changer.parameter;

import hudson.Extension;
import hudson.model.*;
import hudson.util.ListBoxModel;
import jenkins.advancedqueue.PriorityConfiguration;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.logging.Level;
import java.util.logging.Logger;

public class DownstreamPriorityDefinition  extends SimpleParameterDefinition {
    private static final Logger LOGGER = Logger.getLogger(DownstreamPriorityDefinition.class.getName());
    private static final long serialVersionUID = 1L;

    private String downstreamPriority = "-1";

    @DataBoundConstructor
    public DownstreamPriorityDefinition(String downstreamPriority, String name, String description) {
        super(name, description);
        this.downstreamPriority = downstreamPriority;
    }

//    protected DownstreamPriorityDefinition(String name, String description) {
//        super(name, description);
//    }


    @Override
    public ParameterValue createValue(String value) {
        LOGGER.log(Level.FINE, "Kenny createValue 1 name:" + getName() + " value:" + value);
        return new StringParameterValue(getName(), value, getDescription());
    }

    @Override
    public ParameterValue createValue(StaplerRequest req, JSONObject jo) {
        String value = jo.getString("value");
        LOGGER.log(Level.FINE, "Kenny createValue 2 name:" + getName() + " value:" + value);
        return new StringParameterValue(getName(), value, getDescription());
    }

    @Override
    public ParameterValue getDefaultParameterValue() {
        return new StringParameterValue(getName(), "-1");
    }


    @Override
    public ParameterDefinition copyWithDefaultValue(ParameterValue defaultValue) {
        if (defaultValue instanceof StringParameterValue) {
            StringParameterValue value = (StringParameterValue) defaultValue;
            return new DownstreamPriorityDefinition(value.getValue(), getName(), getDescription());
        } else {
            return this;
        }
    }

    public String getDownstreamPriority() {
        return downstreamPriority;
    }

    public static ListBoxModel getPriorites() {
//        List<String> priorites = new LinkedList<>();
        ListBoxModel items = PriorityConfiguration.get().getPriorities();
//        items.forEach(it -> {
//            priorites.add(it.value);
//        });
//        return priorites;
        return items;

    }

    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {
        @Override
        public String getDisplayName() {
            return "DownstreamPriority Parameter";
        }
    }
}
