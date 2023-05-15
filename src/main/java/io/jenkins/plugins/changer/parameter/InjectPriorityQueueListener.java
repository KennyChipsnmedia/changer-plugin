package io.jenkins.plugins.changer.parameter;

import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.queue.QueueListener;
import jenkins.advancedqueue.sorter.ItemInfo;
import jenkins.advancedqueue.sorter.QueueItemCache;
import jenkins.model.Jenkins;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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


    @Override
    public void onLeaveWaiting(Queue.WaitingItem wi) {

        List<Cause> causes = wi.getCauses();

        Loop1:
        for(Cause cause: causes) {
            if(cause instanceof Cause.UpstreamCause) {
                Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause)cause;
                Job upstreamJob = Jenkins.get().getItemByFullName((upstreamCause).getUpstreamProject(), Job.class);
                int buildNumber = (upstreamCause).getUpstreamBuild();
                Run<?, ?> build = upstreamJob.getBuildByNumber(buildNumber);
                if(build != null && build.isBuilding()) {
                    ParametersDefinitionProperty paramDefProp = build.getParent().getProperty(ParametersDefinitionProperty.class);
                    if(paramDefProp != null) {
                        List<ParameterDefinition> definitions = paramDefProp.getParameterDefinitions();

                        for(ParameterDefinition definition: definitions) {
//                            LOGGER.log(Level.INFO, "defintion displayName:" + definition.getDescriptor().getDisplayName() + " name:" + definition.getName());
                            if(definition instanceof DownstreamPriorityDefinition) {
                                List<ParametersAction> actions = build.getActions(ParametersAction.class);

                                for(ParametersAction action: actions) {
                                    ParameterValue pv = action.getParameter(definition.getName());
                                    if (pv != null) {
                                        LOGGER.log(Level.INFO, "parameter name:" + pv.getName() + " value:" + pv.getValue());
                                        ItemInfo itemInfo = QueueItemCache.get().getItem(wi.getId());
                                        if(itemInfo == null) {
                                            LOGGER.log(Level.INFO, "item id:" + wi.getId() + " not cached");
                                        }
                                        else {
                                            if(pv.getValue() == null) {
                                                LOGGER.log(Level.INFO, "pv getValue null");
                                            }
                                            else {
                                                int newPriority = Util.tryParseNumber(pv.getValue().toString(), FAULT_NUMBER).intValue();
                                                if(newPriority != FAULT_NUMBER) {
                                                    itemInfo.setPrioritySelection(newPriority);
                                                    itemInfo.setWeightSelection(newPriority);
                                                    break Loop1;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    }

}