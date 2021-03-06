/**
 * Copyright 2014 Alexey Ragozin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gridkit.jvmtool.heapdump;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import org.gridkit.jvmtool.heapdump.PathStep.Move;
import org.netbeans.lib.profiler.heap.Field;
import org.netbeans.lib.profiler.heap.FieldValue;
import org.netbeans.lib.profiler.heap.Heap;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;
import org.netbeans.lib.profiler.heap.ObjectArrayInstance;
import org.netbeans.lib.profiler.heap.ObjectFieldValue;

public class HeapClusterAnalyzer {

    private final Heap heap;

    private int recursionThreshold = 30;
    private boolean keepClusters = true;
    private boolean keepClusterMembership = false;
    private boolean useBreadthSearch = false;

    private List<TypeFilterStep> interestingTypes = new ArrayList<TypeFilterStep>();
    private List<TypeFilterStep> blacklistedTypes = new ArrayList<TypeFilterStep>();
    private List<PathStep[]> blacklistedMethods = new ArrayList<PathStep[]>();

    private Set<String> rootClasses = new HashSet<String>();
    private List<EntryPoint> entryPoints = new ArrayList<EntryPoint>();
    private Set<String> blacklist = new HashSet<String>();

    private PathListener tooDeepListener = new DeepPathListener();

    private PathListener sharedPathListener = null;

    private RefSet ignoreRefs = new RefSet();
    private RefSet knownRefs = new RefSet();
    private RefSet sharedRefs = new RefSet();

    private List<Cluster> clusters = new ArrayList<Cluster>();
    private Cluster last;

    private HeapHistogram sharedSummary = new HeapHistogram();
    private long sharedErrorMargin = 0;

    public HeapClusterAnalyzer(Heap heap) {
        this.heap = heap;
    }

    public List<Cluster> getClusters() {
        return clusters;
    }

    public HeapHistogram getSharedSummary() {
        return sharedSummary;
    }

    public long getSharedErrorMargin() {
        return sharedErrorMargin;
    }
    
    public void setSharedPathListener(PathListener listener) {
        this.sharedPathListener = listener;
    }

    public void setGraphDepthThreshold(int threshold) {
        this.recursionThreshold = threshold;
    }

    public void useBreadthSearch() {
        useBreadthSearch = true;
    }

    public void keepClusters(boolean enable) {
        keepClusters = enable;
    }

    public void keepClusterMembership(boolean enable) {
        keepClusterMembership = enable;
    }

    /**
     * Process entry point and blacklist configuration
     */
    public void prepare() {
        for(JavaClass jc: heap.getAllClasses()) {
            for(TypeFilterStep it: interestingTypes) {
                if (it.evaluate(jc)) {
                    rootClasses.add(jc.getName());
                }
            }
            for(TypeFilterStep bt: blacklistedTypes) {
                if (bt.evaluate(jc)) {
                    blacklist.add(jc.getName());
                }
            }
            for(PathStep[] bp: blacklistedMethods) {
                TypeFilterStep ts = (TypeFilterStep) bp[0];
                String fn = ((FieldStep) bp[1]).getFieldName();
                if (ts.evaluate(jc)) {
                    for(Field f: jc.getFields()) {
                        if (fn == null || fn.equals(f.getName())) {
                            blacklist.add(jc.getName() + "#" + f.getName());
                        }
                    }
                }
            }
        }
    }

    /**
     * Used to import stored shared set for "shared path" analysis
     * @param instanceId
     */
    public void markShared(long instanceId) {
        sharedRefs.set(instanceId, true);
    }

    public void markIgnored(long instanceId) {
        ignoreRefs.set(instanceId, true);
    }

    public void markIgnored(RefSet refs) {
        ignoreRefs.add(refs);
    }

    public void addEntryPoint(String heapPath) {
        PathStep[] path = HeapPath.parsePath(heapPath, true);
        if (path.length > 0 && path[0] instanceof TypeFilterStep) {
            interestingTypes.add((TypeFilterStep) path[0]);
        }
        else {
            throw new IllegalArgumentException("Invalid entry point '" + heapPath + "'. Entry point should start with type filter.");
        }

        entryPoints.add(new EntryPoint(heapPath, path));
    }

    public void blackList(String pathSuffix) {
        PathStep[] path = HeapPath.parsePath(pathSuffix, true);
        if (path.length == 1 && path[0] instanceof TypeFilterStep) {
            blacklistedTypes.add((TypeFilterStep) path[0]);
        }
        else if (path.length == 2 && path[0] instanceof TypeFilterStep && path[1] instanceof FieldStep) {
            blacklistedMethods.add(path);
        }
        else {
            throw new IllegalArgumentException("Invalid blacklist suffix '" + pathSuffix + "'. Suffix should eigther type predicate or type predicate with method.");
        }
    }

    public void addTokenToBlacklist(String token) {
        blacklist.add(token);
    }

    public ClusterDetails feed(Instance i) {
        if (last != null) {
            if (!keepClusterMembership) {
                last.objects = null;
            }
            last = null; 
        }
        if (rootClasses.isEmpty()) {
            throw new IllegalStateException("Interesting types are not defined");
        }
        String type = i.getJavaClass().getName();
        if (rootClasses.contains(type)) {
            Cluster cluster = new Cluster();
            cluster.root = i;
            cluster.objects = new RefSet();
            cluster.summary = new HeapHistogram();

            analyze(cluster);
            updateKnownMap(cluster);

            if (keepClusters) {
            clusters.add(cluster);
            }

            last = cluster;
            return cluster;
        }
        return null;
    }

    private void updateKnownMap(Cluster cluster) {
        RefSet marked = cluster.objects;
        for(Long id: marked.ones()) {
            if (knownRefs.getAndSet(id, true)) {
                Instance i = heap.getInstanceByID(id);
                sharedErrorMargin += i.getSize();
                if (!sharedRefs.getAndSet(id, true)) {
                    sharedErrorMargin += i.getSize();
                    sharedSummary.accumulate(i);
                }
            }
        }
    }

    private void analyze(Cluster details) {
        if (!useBreadthSearch) {
        StringBuilder path = new StringBuilder();
        for(EntryPoint ep: entryPoints) {
            path.setLength(0);
            path.append("(" + shortName(details.root.getJavaClass().getName()) + ")");
            for(Move i: HeapPath.track(details.root, ep.locator)) {
                path.append(i.pathSpec);
                walk(details, i.instance, path, 0, false, false);
            }
        }
    }
        else {
            for(EntryPoint ep: entryPoints) {
                for(Instance i: HeapPath.collect(details.root, ep.locator)) {
                    widthWalk(details, i, false);
                }
            }

        }
    }

    @SuppressWarnings("unused")
    private void reportSharedPaths(Cluster details) {
        StringBuilder path = new StringBuilder();
        for(EntryPoint ep: entryPoints) {
            path.setLength(0);
            path.append(ep.path);
            for(Instance i: HeapPath.collect(details.root, ep.locator)) {
                walk(details, i, path, 0, true, false);
            }
        }
    }

    @SuppressWarnings("unused")
    private void calculateShared(Cluster details) {
        StringBuilder path = new StringBuilder();
        for(EntryPoint ep: entryPoints) {
            path.setLength(0);
            path.append(ep.path);
            for(Instance i: HeapPath.collect(details.root, ep.locator)) {
                walk(details, i, path, 0, false, true);
            }
        }
    }

    private void walk(Cluster details, Instance i, StringBuilder path, int depth, boolean reportShared, boolean accountShared) {
        int len = path.length();
        try {
            if (i == null) {
                return;
            }
            if (ignoreRefs.get(i.getInstanceId())) {
                return;
            }
            if (blacklist.contains(i.getJavaClass().getName())) {
                return;
            }
            if (details.objects.getAndSet(i.getInstanceId(), true)) {
                return;
            }
            @SuppressWarnings("unused")
            String type = i.getJavaClass().getName();

            if (!accountShared || sharedRefs.get(i.getInstanceId())) {
                details.summary.accumulate(i);
            }

            if (depth == recursionThreshold) {
                if (tooDeepListener != null) {
                    tooDeepListener.onPath(details.root, path.toString(), i);
                }
                return;
            }

            if (sharedRefs.get(i.getInstanceId())) {
                if (sharedPathListener != null) {
                    sharedPathListener.onPath(details.root, path.toString(), i);
                }
                return;
            }

            if (i instanceof ObjectArrayInstance) {
                ObjectArrayInstance array = (ObjectArrayInstance) i;
                if (!isBlackListedArray(array.getJavaClass())) {
                    int n = 0;
                    for(Long ref: array.getValueIDs()) {
                        if (ref != 0) {
                            // early check to avoid needless instantiation
                            if (!ignoreRefs.get(ref) && !details.objects.get(ref)) {
                                path.setLength(len);
                                path.append('[').append(n).append(']');
                                walk(details, heap.getInstanceByID(ref), path, depth + 1, reportShared, accountShared);
                            }
                        }
                        ++n;
                    }
                }
            }
            else {
                for(FieldValue f: i.getFieldValues()) {
                    String fieldName = f.getField().getName();
                    if (f instanceof ObjectFieldValue) {
                        ObjectFieldValue of = (ObjectFieldValue) f;
                        if (!isBlackListed(of.getField())) {
                            long id = of.getInstanceId();
                            // early check to avoid instantiation
                            if (!ignoreRefs.get(id)) {
                                path.setLength(len);
                                path.append('.').append(fieldName);
                                walk(details, of.getInstance(), path, depth + 1, reportShared, accountShared);
                            }
                        }
                    }
                }
            }
        }
        catch(RuntimeException e) {
            System.err.println("Fail path < " + path + " > ending with " + i.getJavaClass().getName());
            throw e;
        }
        finally {
            path.setLength(len);
        }
    }

    private void widthWalk(Cluster details, Instance root, boolean accountShared) {
        RefSet queue = new RefSet();
        queue.set(root.getInstanceId(), true);
        @SuppressWarnings("unused")
        long count = 0;
        while(true) {
            long n = queue.seekOne(1); // 0 is null, so ignore it
            if (n < 0) {
                break;
            }
            while(true) {
                long id = queue.seekOne(n);
                if (id < 0) {
                    break;
                }
                else {
                    n = id + 1;
                }

                queue.set(id, false);
                if (ignoreRefs.get(id)) {
                    continue;
                }

                if (details.objects.getAndSet(id, true)) {
                    continue;
                }

                Instance i = heap.getInstanceByID(id);
                if (blacklist.contains(i.getJavaClass().getName())) {
                    continue;
                }

                ++count;

                @SuppressWarnings("unused")
                String type = i.getJavaClass().getName();

                if (!accountShared || sharedRefs.get(i.getInstanceId())) {
                    details.summary.accumulate(i);
                }

                if (i instanceof ObjectArrayInstance) {
                    ObjectArrayInstance array = (ObjectArrayInstance) i;
                    if (!isBlackListedArray(array.getJavaClass())) {
                        for(Long ref: array.getValueIDs()) {
                            if (ref != 0) {
                                // early check to avoid needless instantiation
                                if (!ignoreRefs.get(ref) && !details.objects.get(ref)) {
                                    queue.set(ref, true);
                                }
                            }
                        }
                    }
                }
                else {
                    for(FieldValue f: i.getFieldValues()) {
                        @SuppressWarnings("unused")
                        String fieldName = f.getField().getName();
                        if (f instanceof ObjectFieldValue) {
                            ObjectFieldValue of = (ObjectFieldValue) f;
                            if (!isBlackListed(of.getField())) {
                                long ref = of.getInstanceId();
                                // early check to avoid instantiation
                                if (!ignoreRefs.get(ref) && !details.objects.get(ref)) {
                                    queue.set(ref, true);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isBlackListedArray(JavaClass type) {
        String tn = type.getName() + "[*]";
        return blacklist.contains(tn);
    }

    private boolean isBlackListed(Field field) {
        String tn = field.getDeclaringClass().getName() + "#" + field.getName();
        return blacklist.contains(tn);
    }

    private static final String shortName(String name) {
        int c = name.lastIndexOf('.');
        if (c >= 0) {
            return "**." + name.substring(c + 1);
        }
        else {
            return name;
        }
    }

    private final class DeepPathListener implements PathListener {
        @Override
        public void onPath(Instance root, String path, Instance shared) {
            PathStep[] chain = HeapPath.parsePath(path, true);
            StringBuilder sb = new StringBuilder();
            Instance o = root;
            for(int i = 0; i != chain.length; ++i) {
                if (chain[i] instanceof TypeFilterStep) {
                    continue;
                }
                sb.append("(" + shortName(o.getJavaClass().getName()) + ")");
                try {
                Move m = chain[i].track(o).next();
                sb.append(m.pathSpec);
                o = m.instance;
            }
                catch(NoSuchElementException e) {
                    sb.append("{failed: " + chain[i] + "}");
                }
            }

            System.err.println("DEEP REF: " + root.getInstanceId() + " " + sb);
        }
    }

    public interface ClusterDetails {

        public Instance getRootInstance();

        public HeapHistogram getHistogram();

        public HeapHistogram getSharedSummary();

        public RefSet getAllObjects();

    }

    public interface Summary {

        public long getInstanceCount();

        public long getTotalSize();

        public HeapHistogram getHistogram();

        public RefSet getAllObjects();

    }

    public interface PathListener {

        public void onPath(Instance root, String path, Instance shared);

    }

    public interface PathExpander {

        public Iterator<Instance> onPath(Instance root, String path, Instance shared);
        
    }
    
    private static class Cluster implements ClusterDetails {

        Instance root;
        RefSet objects;
        HeapHistogram summary;
        HeapHistogram sharedSummary;

        @Override
        public Instance getRootInstance() {
            return root;
        }

        @Override
        public HeapHistogram getHistogram() {
            return summary;
        }
        @Override
        public HeapHistogram getSharedSummary() {
            return sharedSummary;
        }
        @Override
        public RefSet getAllObjects() {
            return objects;
        }
    }

    private static class EntryPoint {
        String path;
        PathStep[] locator;

        public EntryPoint(String path, PathStep[] locator) {
            this.path = path;
            this.locator = locator;
        }
    }

}
