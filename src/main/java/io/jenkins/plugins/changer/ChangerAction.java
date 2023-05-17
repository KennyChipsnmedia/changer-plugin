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
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ChangerAction implements Action {
    private static final Logger LOGGER = Logger.getLogger(ChangerAction.class.getName());
    private final Run target;
    private List<Run> runList;

    public ChangerAction(Run run, List<Run> runList) {
        this.target = run;
        this.runList = runList;

    }

    @Override
    public String getIconFileName() {
        return "/plugin/changer/img/stop.png";
    }

    @Override
    public String getDisplayName() {
        return "Changer";
    }

    @Override
    public String getUrlName() {
        return "changer";
    }

    private void filterRunAndItems(StaplerRequest req, Map<Integer, Run> runs, Map<Long, Queue.Item> items) {
        LOGGER.log(Level.INFO, "debug req paramerters");
        req.getParameterMap().entrySet().forEach(entry -> {
            LOGGER.log(Level.INFO, "key:" + entry.getKey() + " v:" + entry.getValue());
        });

        List<String> qtems = new ArrayList<>();
        List<String> builds = new ArrayList<>();;

        if(req.getParameter("qtems") != null) {
            LOGGER.log(Level.INFO, "Reuqested cancel, raw qtems=>" + req.getParameter("qtems"));
            qtems = Arrays.asList(req.getParameter("qtems").split(","));
        }
        if(req.getParameter("builds") != null) {
            LOGGER.log(Level.INFO, "Reuqested abort, raw builds=>" + req.getParameter("builds"));
            builds =  Arrays.asList(req.getParameter("builds").split(","));
        }

        Map<Integer, Run> newRuns = new LinkedHashMap<>();
        Map<Long, Queue.Item> newItems = new LinkedHashMap<>();

        builds.stream().filter(it -> it.trim().length() > 0).forEach(it -> {
            String[] parray = it.split(":");
            if(parray.length > 0) {
                String tmpBuildNumber = parray[0];
                int buildNumber = Util.tryParseNumber(tmpBuildNumber, -1).intValue();
                Run r = runs.get(buildNumber);
                if(r != null) {
                    newRuns.put(r.getNumber(), r);
                }
            }
        });


        qtems.stream().filter(it -> it.trim().length() > 0).forEach(it -> {
            String[] parray = it.split(":");
            if(parray.length > 0) {
                String tmpId = parray[0];
                long id = Util.tryParseNumber(tmpId, -1).longValue();
                Queue.Item  item = items.get(id);
                if(item != null) {
                    newItems.put(id, item);
                }
            }
        });

        runs.clear();;
        runs.putAll(newRuns);

        items.clear();;
        items.putAll(newItems);

    }

    private static boolean isQueueItemCausedBy(Queue.Item item, Run run) {
        return run != null && item != null ? item.getCauses().stream().anyMatch((cause) -> {
            return cause instanceof Cause.UpstreamCause && ((Cause.UpstreamCause)cause).pointsTo(run);
        }) : false;
    }

    private void fetchRunAndItems(boolean isFirst, Run run, Map<Integer, Run> runs, Map<Long, Queue.Item> items) {
        boolean canDismiss = true;
        if(isFirst) {
            // do nothing
            /*
            if(run.isBuilding()) {
                runs.put(run.getNumber(), run);
            }
            else {
                if(run.getParent().getQueueItem() != null) {
                    long runId = run.getParent().getQueueItem().getId();
                    Arrays.stream(Jenkins.get().getQueue().getItems()).forEach(it -> {
                        if(it.getId() == runId) {
                            items.put(it.getId(), it);
                        }
                    });
                }
            }
            */
        }
        BuildCache.getCache().getDownstreamBuilds(run).forEach(r -> {
            runs.put(r.getNumber(), r);
            fetchRunAndItems(false, r, runs, items);
        });

        if(run.getParent() instanceof Queue.Task) {
            Arrays.stream(Queue.getInstance().getItems()).forEach(it -> {
                if(isQueueItemCausedBy(it, run)) {
                    items.put(it.getId(), it);
                }
            });
        }
    }

    public List<Run> getRunList() {
        LOGGER.log(Level.FINE, target.getParent().getFullDisplayName() + " runList size:" + runList.size());
        runList.forEach(r -> {
            LOGGER.log(Level.FINE, target.getParent().getFullDisplayName() + " number:" + r.getNumber());
        });

        return runList;
    }

    public List<Integer> getNumbers() {
        List<Integer> numbers = new ArrayList<Integer>();
        runList.forEach(r -> {
            LOGGER.log(Level.FINE, target.getParent().getFullDisplayName() + " number:" + r.getNumber());
            numbers.add(r.getNumber());
        });
        return numbers;
    }

    public ListBoxModel getPriorities() {
        ListBoxModel items = PriorityConfiguration.get().getPriorities();
        LOGGER.log(Level.FINE, "getPriorites items size:" + items.size());
        items.forEach(item -> {
            LOGGER.log(Level.FINE, "priority name:" + item.name + " value:" + item.value);
        });
        return items;
    }

    public List<String> getNodes() {
        List<String> nodes = new ArrayList<>();
        nodes.add("built-in");
        Jenkins.get().getNodes().forEach(n -> {
            nodes.add(n.getNodeName());
        });
        return nodes;
    }

    public Map<String, String> getChildren(int buildNumber) {
        Map<String, String> children= new HashMap<>();
        children.put("runs", "");
        children.put("items", "");

        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return children;
        }

        //
        Map<Long, Queue.Item> items = new LinkedHashMap<>();
        Map<Integer, Run> runs = new LinkedHashMap<>();


        Run target = optRun.get();
        String fullDisplayName = target.getFullDisplayName();
        String displayName = target.getDisplayName();
        int number = target.getNumber();

        LOGGER.log(Level.INFO, "TARGET fullDisplayName:" + fullDisplayName +" displayName:" + displayName + " number:" + number);

        fetchRunAndItems(true, target, runs, items);

        LOGGER.log(Level.INFO, "items ==>");
        items.entrySet().forEach(it -> LOGGER.log(Level.INFO, "item id:" + it.getValue().getId() + " name:" + it.getValue().task.getFullDisplayName()));

        LOGGER.log(Level.INFO, "runs ==>");
        runs.entrySet().forEach(it -> LOGGER.log(Level.INFO, "run buildNumber:" + it.getValue().getNumber() + " name:" + it.getValue().getParent().getFullDisplayName()));

        StringBuilder sb = new StringBuilder();

        runs.entrySet().forEach(r -> {
            if(r.getValue().isBuilding()) {
                sb.append(r.getValue().getNumber());
                sb.append(":");
                sb.append(r.getValue().getParent().getFullDisplayName());

                // priority
                ItemInfo itemInfo = StartedJobItemCache.get().getStartedItem(r.getValue().getParent().getFullDisplayName(), r.getValue().getNumber());
                if(itemInfo != null) {
                    sb.append(":");
                    sb.append("P" + itemInfo.getPriority());
                }
                sb.append(",");
            }
        });

        children.put("runs", sb.toString());
        sb.setLength(0);


        items.entrySet().forEach(i -> {
            sb.append(i.getValue().getId());
            sb.append(":");

            sb.append(i.getValue().task.getName());

            // priority
            ItemInfo itemInfo = QueueItemCache.get().getItem(i.getValue().getId());
            if(itemInfo == null) {
                LOGGER.log(Level.WARNING, "item info null cannot display priority for item id:" + i.getValue().getId());
            }
            else {
                sb.append(":");
                int curPriority = itemInfo.getPriority();
                sb.append("P" + curPriority);
            }

            // label
            Label label = i.getValue().getAssignedLabel();
            if(label == null) {
//                LOGGER.log(Level.WARNING, "label null cannot display label for item id:" + i.getValue().getId());
            }
            else {
                sb.append(":");
                sb.append("L" + label.getName());
            }

            sb.append(",");
            if(i.getValue().isBlocked()) {
                LOGGER.log(Level.INFO, i.getValue().task.getName() + " blocked");
            }
            if(i.getValue().isStuck()) {
                LOGGER.log(Level.INFO, i.getValue().task.getName() + " stucked");
            }


        });

        children.put("items", sb.toString());

        return children;
    }

    interface worker {
        void doit();
    }

    private void processRequest(StaplerRequest req, StaplerResponse rsp, int flag) {
        String tmpBuildNumber = req.getParameter("buildNumber");

        if(StringUtils.isBlank(tmpBuildNumber)) {
            return;
        }

        int buildNumber = Integer.parseInt(tmpBuildNumber);

        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return;
        }

        //
        Run target = optRun.get();
        Map<Long, Queue.Item> items = new LinkedHashMap<>();
        Map<Integer, Run> runs = new LinkedHashMap<>();

        fetchRunAndItems(true, target, runs, items);
//        filterRunAndItems(req, runs, items);

        worker w1 = () -> {
            items.entrySet().forEach(i -> {
                LOGGER.log(Level.INFO, "cancel job:" + i.getValue().task.getName());
                try {
                    Jenkins.get().getQueue().cancel(i.getValue());
                }
                catch (Exception e) {
                    LOGGER.log(Level.SEVERE, i.getValue().task.getName() + " cancel error");
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            });
        };

        worker w2 = () -> {
            runs.entrySet().forEach(r -> {
                if(r != null && r.getValue().isBuilding()) {
                    LOGGER.log(Level.INFO, "abort job:" + r.getValue().getFullDisplayName());
                    try {
                        r.getValue().getExecutor().interrupt(Result.ABORTED);
                    }
                    catch (Exception e) {
                        LOGGER.log(Level.SEVERE, r.getValue().getFullDisplayName() + " abort error");
                        LOGGER.log(Level.SEVERE, e.getMessage(), e);
                    }
                }
            });
        };

        if(flag == 1) {
            w1.doit();
        }

        else if(flag == 2) {

            w2.doit();
        }
        else if(flag == 3) {
            w1.doit();
            w2.doit();
        }

    }

    @RequirePOST
    public void doCancel(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        processRequest(req, rsp, 1);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doAbort(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        processRequest(req, rsp, 2);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doCancelAndAbort(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        processRequest(req, rsp, 1);
        processRequest(req, rsp, 2);
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doUpdatePriority(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        String tmpBuildNumber = req.getParameter("buildNumber");
        if(StringUtils.isBlank(tmpBuildNumber)) {
            rsp.forwardToPreviousPage(req);
            return;
        }


        String priority = req.getParameter("priority");
        if(StringUtils.isBlank(priority)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        //
        int buildNumber = Integer.parseInt(tmpBuildNumber);
        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return;
        }

        Run target = optRun.get();
        Map<Long, Queue.Item> items = new LinkedHashMap<>();
        Map<Integer, Run> runs = new LinkedHashMap();

        fetchRunAndItems(true, target, runs, items);
//        filterRunAndItems(req, runs, items);

        int tmpPriority = Integer.parseInt(priority);
        int newPriority = tmpPriority == -1 ? PrioritySorterConfiguration.get().getStrategy().getDefaultPriority() : tmpPriority;

        items.entrySet().forEach(i -> {
            ItemInfo itemInfo = QueueItemCache.get().getItem(i.getValue().getId());
            itemInfo.setPrioritySelection(newPriority);
            itemInfo.setWeightSelection(newPriority);
        });

        Jenkins.get().getQueue().scheduleMaintenance();

        rsp.forwardToPreviousPage(req);

    }

    @RequirePOST
    public void doUpdateLabel(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        String tmpBuildNumber = req.getParameter("buildNumber");
        if(StringUtils.isBlank(tmpBuildNumber)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        String label = req.getParameter("label");
        if(StringUtils.isBlank(label)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        //
        int buildNumber = Integer.parseInt(tmpBuildNumber);
        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return;
        }

        Run target = optRun.get();
        Map<Long, Queue.Item> items = new LinkedHashMap<>();
        Map<Integer, Run> runs = new LinkedHashMap<>();


        fetchRunAndItems(true, target, runs, items);
//        filterRunAndItems(req, runs, items);


        items.entrySet().forEach(i -> {
            Iterator<ParametersAction> actions = i.getValue().getActions(ParametersAction.class).iterator();
            List<ParameterValue> pvs = new LinkedList<>();

            while(actions.hasNext()) {
                ParametersAction action = actions.next();

                Iterator<ParameterValue> params = action.getAllParameters().iterator();
                while(params.hasNext()) {
                    ParameterValue pv = params.next();
                    if(pv instanceof LabelParameterValue) {
                        LOGGER.log(Level.INFO, "item:" + i.getValue().task.getName() + " removing parameter:" + pv.getName() + " action:" + action.getDisplayName());
                        i.getValue().removeAction(action);
                    }
                    else {
                        pvs.add(pv);
                    }
                }
            }

            if(label.equals("default")) {
                // do not add any label, all labels are removed.
            }
            else {
                LabelParameterValue paramValue = new LabelParameterValue(label, label, false, new AllNodeEligibility());
                pvs.add(paramValue);
            }
            ParametersAction pa = new ParametersAction(pvs);
            i.getValue().addAction(pa);

            // debug check remove/add
            /*
            i.getValue().getActions(ParametersAction.class).forEach(it -> {
                List<ParameterValue> parameters = it.getAllParameters();
                parameters.forEach(pv -> {
                    LOGGER.log(Level.INFO, "Kenny debug2 parameter:" + pv.getName());
                });
            });
            */
        });


//        Jenkins.get().getQueue().scheduleMaintenance();
        rsp.forwardToPreviousPage(req);
    }

    @RequirePOST
    public void doUpdateNode(StaplerRequest req, StaplerResponse rsp) throws ServletException, IOException {
        String tmpBuildNumber = req.getParameter("buildNumber");
        if(StringUtils.isBlank(tmpBuildNumber)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        String node = req.getParameter("node");
        if(StringUtils.isBlank(node)) {
            rsp.forwardToPreviousPage(req);
            return;
        }

        //
        int buildNumber = Integer.parseInt(tmpBuildNumber);
        Optional<Run> optRun = runList.stream().filter(r -> r.getNumber() == buildNumber).findAny();
        if(optRun.isEmpty()) {
            return;
        }

        Run target = optRun.get();
        Map<Long, Queue.Item> items = new LinkedHashMap<>();
        Map<Integer, Run> runs = new LinkedHashMap<>();

        fetchRunAndItems(true, target, runs, items);
//        filterRunAndItems(req, runs, items);

        Node newNode = Jenkins.get().getNode(node);
        if(newNode != null) {
            items.entrySet().forEach(i -> {

                // NodeLabelParameter plugin use ParametersAction as LabelAssignmentAction.
                // If exists LabelParameterValue, remove it.
                i.getValue().getActions(ParametersAction.class).forEach(action -> {
                    List<ParameterValue> parameters = action.getAllParameters();
                    parameters.forEach(p -> {
                        if(p instanceof LabelParameterValue) {
                            i.getValue().removeAction(action);
                        }
                    });
                });

                // Add requested node
                List<String> labels = new ArrayList<>();
                labels.add(newNode.getNodeName());
                NodeParameterValue paramValue = new NodeParameterValue(newNode.getNodeName(), labels, new AllNodeEligibility());
                ParametersAction parametersAction = new ParametersAction(paramValue);
                i.getValue().addAction(parametersAction);
            });
        }
        else {
            if(node.equals("built-in") || node.equals("default")) {
                items.entrySet().forEach(i -> {
                    i.getValue().getActions(ParametersAction.class).forEach(action -> {
                        action.getAllParameters().forEach(p -> {
                            if(p instanceof LabelParameterValue) {
                                i.getValue().removeAction(action);
                                LOGGER.log(Level.INFO, "item:" + i.getValue().task.getName() + " built-in, old paramAction removed");
                            }
                        });
                    });

                });

            }
            else { // for debugging
                LOGGER.log(Level.SEVERE, "node:" + node + " not found");
                LOGGER.log(Level.INFO, "Jenkins has nodes =>");
                for(Node n: Jenkins.get().getNodes()) {
                    LOGGER.log(Level.INFO, n.getNodeName());
                }
                LOGGER.log(Level.INFO, "<= Jenkins has nodes");
            }

        }

        Jenkins.get().getQueue().scheduleMaintenance();


        rsp.forwardToPreviousPage(req);
    }


    @Extension
    public static class RunChangerActionFactory extends TransientActionFactory<Run> {

        @Override
        public Class<Run> type() {
            return Run.class;
        }

        @Override
        public Collection<? extends Action> createFor(Run run) {

            List<Run> runList = new ArrayList<>();
            runList.add(run);
            return Collections.singleton(new ChangerAction(run, runList));
        }
    }


    @Extension(ordinal = 1000)
    public static class JobChangerActionFactory extends TransientActionFactory<Job> {

        @Override
        public Class<Job> type() {
            return Job.class;
        }

        @Override
        public Collection<? extends Action> createFor(@Nonnull Job target) {

            LOGGER.log(Level.FINE, "Getting buildlist start for target:" + target.getName());
//            List<Run> runList = target.getBuilds();
            List<Run> runList = target.getNewBuilds();
            LOGGER.log(Level.FINE, "Getting buildlist end for target:" + target.getName());
            runList = runList.stream().filter(r -> r.isBuilding()).collect(Collectors.toList());
//            runList = ((RunList<Run>) runList).filter(r -> r.isBuilding());
            LOGGER.log(Level.FINE, "Filtered on building for target:" + target.getName());
            ArrayList<Run> runArrayList = new ArrayList<>(runList);

            Collections.sort(runArrayList, new Comparator<Run>() {
                @Override
                public int compare(Run o1, Run o2) {
                    return o2.getNumber() - o1.getNumber();
                }
            });

            Run build = target.getLastBuild();

            if (build == null) {
                return Collections.emptyList();
            } else {
                return Collections.singleton(new ChangerAction(build, runArrayList));
            }
        }
    }

}
