package io.jenkins.plugins.changer.parameter;

import com.axis.system.jenkins.plugins.downstream.cache.BuildCache;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.queue.QueueListener;
import jenkins.advancedqueue.PrioritySorterConfiguration;
import jenkins.advancedqueue.sorter.ItemInfo;
import jenkins.advancedqueue.sorter.QueueItemCache;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;
import org.springframework.security.core.userdetails.UserCache;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Extension
public class InjectPriorityQueueListener extends QueueListener {
    private final static Logger LOGGER = Logger.getLogger(InjectPriorityQueueListener.class.getName());
    private final static int FAULT_NUMBER = -2;

    /**
     * private static boolean isQueueItemCausedBy(Queue.Item item, Run run) {
     *         return run != null && item != null ? item.getCauses().stream().anyMatch((cause) -> {
     *             return cause instanceof Cause.UpstreamCause && ((Cause.UpstreamCause)cause).pointsTo(run);
     *         }) : false;
     *     }
     * @param wi
     */
    @SuppressFBWarnings
    @Override
    public void onLeaveWaiting(Queue.WaitingItem wi) {
        int defaultPriority = PrioritySorterConfiguration.get().getStrategy().getDefaultPriority();

        List<Cause.UpstreamCause> upstreamCauses = wi.getCauses().stream().filter(it -> it instanceof Cause.UpstreamCause).map(it -> (Cause.UpstreamCause)it).collect(Collectors.toList());
        List<Cause> rebuildCauses  = wi.getCauses().stream().filter(it -> it.getShortDescription().contains("Rebuilds build")).collect(Collectors.toList());

        if(!upstreamCauses.isEmpty()) {
            if(!rebuildCauses.isEmpty()) { // triggered by rebuild plugin
                LOGGER.log(Level.INFO, "Triggerd by Rebuild, task:{0}", wi.task.getName());
                Job<?, ?> job = Jenkins.get().getItemByFullName(wi.task.getName(), Job.class);
                Run<?, ?> lastBuild = job.getLastBuild();
                Set<Run> downstreamBuilds = BuildCache.getCache().getDownstreamBuilds(lastBuild);

                // Only executed when no downstream builds exist.
                if(downstreamBuilds.isEmpty()) {
                    ParametersDefinitionProperty pdp = job.getProperty(ParametersDefinitionProperty.class);
                    List<ParameterDefinition> definitions = pdp.getParameterDefinitions().stream().filter(it -> it instanceof DownstreamPriorityDefinition).collect(Collectors.toList());
                    if(!definitions.isEmpty()) {
                        DownstreamPriorityDefinition definition = (DownstreamPriorityDefinition)definitions.get(0);
                        ParametersAction action = wi.getAction(ParametersAction.class);
                        ParameterValue pv = action.getParameter(definition.getName());

                        setJobPriority(wi, pv, defaultPriority);
                    }
                }
            }
            else {
                Cause.UpstreamCause upstreamCause = upstreamCauses.get(0);
                Job upstreamJob = Jenkins.get().getItemByFullName((upstreamCause).getUpstreamProject(), Job.class);
                int buildNumber = upstreamCause.getUpstreamBuild();
                Run<?, ?> build = upstreamJob.getBuildByNumber(buildNumber);

                if(build != null) {
//                if(build != null && build.isBuilding()) {
                    ParametersDefinitionProperty pdp = build.getParent().getProperty(ParametersDefinitionProperty.class);
                    List<ParameterDefinition> definitions = pdp.getParameterDefinitions().stream().filter(it -> it instanceof DownstreamPriorityDefinition).collect(Collectors.toList());
                    if(!definitions.isEmpty()) {
                        DownstreamPriorityDefinition definition = (DownstreamPriorityDefinition)definitions.get(0);
                        ParametersAction action = build.getAction(ParametersAction.class);
                        ParameterValue pv = action.getParameter(definition.getName());
                        setJobPriority(wi, pv, defaultPriority);
                    }
                }
            }
        }
    }

    private void setJobPriority(Queue.WaitingItem wi, ParameterValue pv, int defaultPriority) {
        if (pv != null) {
            LOGGER.log(Level.FINEST, "item:" + wi.task.getName() +  " parameter name:" + pv.getName() + " value:" + pv.getValue());
            ItemInfo itemInfo = QueueItemCache.get().getItem(wi.getId());
            if(itemInfo == null) {
                LOGGER.log(Level.INFO, "item id:" + wi.getId() + " not cached");
            }
            else {
                if(pv.getValue() == null) {
                    LOGGER.log(Level.INFO, "pv getValue null");
                }
                else {
                    int newPriority = Integer.parseInt(String.valueOf(Util.tryParseNumber(String.valueOf(pv.getValue()), FAULT_NUMBER)));
                    if(newPriority != FAULT_NUMBER) {
                        LOGGER.log(Level.FINEST, "item:{0} pname:{1}, pvalue:{2}", new Object[]{wi.task.getName(), pv.getName(), pv.getValue()});
                        if(newPriority == -1) {
                            newPriority = defaultPriority;
                        }
                        itemInfo.setPrioritySelection(newPriority);
                        itemInfo.setWeightSelection(newPriority);

                        // just for viewing information. after build done.
                        Iterator<ParametersAction> actions = wi.getActions(ParametersAction.class).iterator();
                        List<ParameterValue> pvs = new LinkedList<>();
                        while(actions.hasNext()) {
                            ParametersAction action = actions.next();
                            Iterator<ParameterValue> params = action.getAllParameters().iterator();
                            while (params.hasNext()) {
                                ParameterValue param = params.next();
                                if(param instanceof DownstreamPriorityParameterValue) {
                                    DownstreamPriorityParameterValue dppv = new DownstreamPriorityParameterValue(param.getName(), String.valueOf(newPriority), param.getDescription());
                                    pvs.add(dppv);
                                    wi.removeAction(action);
                                }
                                else {
                                    pvs.add(param);
                                }
                            }
                        }
                        ParametersAction pa = new ParametersAction(pvs);
                        wi.addAction(pa);

                    }
                }
            }
        }

    }

}