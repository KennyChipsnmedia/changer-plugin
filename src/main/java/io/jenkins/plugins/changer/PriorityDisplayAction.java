package io.jenkins.plugins.changer;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.Queue;
import hudson.util.ListBoxModel;
import hudson.util.RunList;
import jenkins.advancedqueue.PriorityConfiguration;
import jenkins.advancedqueue.PrioritySorterConfiguration;
import jenkins.advancedqueue.sorter.ItemInfo;
import jenkins.advancedqueue.sorter.QueueItemCache;
import jenkins.advancedqueue.sorter.StartedJobItemCache;
import jenkins.model.Jenkins;
import jenkins.model.TransientActionFactory;
import org.apache.commons.lang.StringUtils;
import org.jvnet.jenkins.plugins.nodelabelparameter.LabelParameterValue;
import org.jvnet.jenkins.plugins.nodelabelparameter.NodeParameterValue;
import org.jvnet.jenkins.plugins.nodelabelparameter.node.AllNodeEligibility;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.ExportedBean;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import jenkins.advancedqueue.sorter.StartedJobItemCache;
@SuppressWarnings("unused")
@ExportedBean
public class PriorityDisplayAction implements Action {
    private static final Logger LOGGER = Logger.getLogger(PriorityDisplayAction.class.getName());
    private final Run target;
    private List<Run> runList;

    private PriorityDisplayAction(Run run, List<Run> runList) {
        this.target = run;
        this.runList = runList;
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getDisplayName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "priority_display";
    }

    public String getPriorities() {
        StringBuilder sb = new StringBuilder();

        LOGGER.log(Level.INFO, "Getting priorities for job:" + target.getParent().getFullDisplayName());

        runList.stream().forEach(r -> {
            LOGGER.log(Level.INFO, "r:" + r.getParent().getFullDisplayName());
            ItemInfo itemInfo = StartedJobItemCache.get().getStartedItem(r.getParent().getFullDisplayName(), r.getNumber());
            if(itemInfo != null) {
                int buildNumber = r.getNumber();
                int priroty = itemInfo.getPriority();
                sb.append("#");
                sb.append(buildNumber);
                sb.append("P");
                sb.append(priroty);
                sb.append(",");
            }
        });

        LOGGER.log(Level.INFO, "priorities:" + sb.toString());
        if(sb.toString().length() == 0) {
            return "No information, check this job is on building.";
        }
        else {
            return sb.toString();
        }

//        return sb.toString();

    }

    @Extension
    public static class RunPriorityDisplayActionFactory extends TransientActionFactory<Run> {

        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @Override
        public Collection<? extends Action> createFor(Run run) {
            List<Run> runList = new ArrayList<>();
            runList.add(run);
            return Collections.singleton(new PriorityDisplayAction(run, runList));
        }
    }



    @Extension(ordinal = 1000)
    public static class JobPriorityDisplayActionFactory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Collection<? extends Action> createFor(@Nonnull Job target) {

//            List<Run> runList = target.getBuilds();
            List<Run> runList = target.getNewBuilds();
            runList = runList.stream().filter(r -> r.isBuilding()).collect(Collectors.toList());

            ArrayList<Run> runArrayList = new ArrayList<>(runList);

            Collections.sort(runArrayList, new Comparator<Run>() {
                public int compare(Run o1, Run o2) {
                    return o2.getNumber() - o1.getNumber();
                }
            });

            Run build = target.getLastBuild();

            if (build == null) {
                return Collections.emptyList();
            } else {
                return Collections.singleton(new PriorityDisplayAction(build, runArrayList));
            }
        }
    }

}
