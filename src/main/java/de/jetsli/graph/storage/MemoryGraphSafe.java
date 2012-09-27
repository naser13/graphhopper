/*
 *  Copyright 2012 Peter Karich 
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package de.jetsli.graph.storage;

import de.jetsli.graph.coll.MyBitSet;
import de.jetsli.graph.coll.MyBitSetImpl;
import de.jetsli.graph.coll.MyOpenBitSet;
import de.jetsli.graph.routing.util.CarStreetType;
import de.jetsli.graph.util.EdgeIterator;
import de.jetsli.graph.util.Helper;
import de.jetsli.graph.util.shapes.BBox;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.hash.TIntHashSet;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This graph implementation is memory efficient and fast (in that order), and read-thread safe.
 * Also it is easier to maintain compared to the MMapGraph. It allows storage via flush and loads
 * from storage if it exists. It allows duplicate edges.
 *
 * @author Peter Karich
 */
public class MemoryGraphSafe implements Graph {

    protected static final int EMPTY_LINK = 0;
    private static final float DIST_UNIT = 10000f;
    // number of integers not edges
    private static final int MIN_SEGMENT_SIZE = 1 << 13;
    private static final float FACTOR = 1.5f;
    // EDGES LAYOUT - keep in mind that we address integers here - not bytes!
    // one edge is referenced by two nodes A and B, where it is id(A) < id(B). flags are relative to A
    protected static final int LEN_NODEA_ID = 1;
    protected static final int LEN_NODEB_ID = 1;
    protected static final int LEN_LINKA = 1;
    protected static final int LEN_LINKB = 1;
    protected static final int LEN_FLAGS = 1;
    protected static final int LEN_DIST = 1;
    // shortcut node
    protected static final int LEN_SCNODE = 1;
    private static final int LEN_EDGE = LEN_NODEA_ID + LEN_NODEB_ID + LEN_LINKA + LEN_LINKB
            + LEN_FLAGS + LEN_DIST + LEN_SCNODE;
    protected Logger logger = LoggerFactory.getLogger(getClass());
    // nodes
    private float[] lats;
    private float[] lons;
    private int[] refToEdges;
    // edges
    private int[][] edgesSegments;
    private int edgesSegmentSize;
    private int edgeCurrentSegment;
    private int edgeNextGlobalPointer;
    // some properties
    private long creationTime = System.currentTimeMillis();
    private int size;
    private String storageLocation;
    private MyBitSet deletedNodes;
    private BBox bounds;

    public MemoryGraphSafe(int cap) {
        this(null, cap);
    }

    public MemoryGraphSafe(String storageDir, int cap) {
        this(storageDir, cap, 2 * cap);
    }

    public MemoryGraphSafe(String storageDir, int cap, int capEdge) {
        this.storageLocation = storageDir;
        this.bounds = BBox.INVERSE.clone();
        if (!loadExisting(storageDir)) {
            initNodes(cap);
            initEdges(capEdge);
        }
    }

    int getSegmentSize() {
        return edgesSegmentSize;
    }

    int getSegments() {
        return edgesSegments.length;
    }

    @Override public int getNodes() {
        // readLock.lock();
        return size;
    }

    @Override
    public BBox getBounds() {
        return bounds;
    }

    private int getMaxEdges() {
        return edgesSegments.length * edgesSegmentSize / LEN_EDGE;
    }

    @Override
    public void setNode(int index, double lat, double lon) {
        // writeLock.lock();
        ensureNodeIndex(index);
        lats[index] = (float) lat;
        lons[index] = (float) lon;
        if (lat > bounds.maxLat)
            bounds.maxLat = lat;
        if (lat < bounds.minLat)
            bounds.minLat = lat;
        if (lon > bounds.maxLon)
            bounds.maxLon = lon;
        if (lon < bounds.minLon)
            bounds.minLon = lon;
    }

    private void initEdges(int cap) {
        int tmp = (int) (Math.log(cap * LEN_EDGE) / Math.log(2));
        edgesSegmentSize = Math.max((int) Math.pow(2, tmp), MIN_SEGMENT_SIZE);
        edgesSegments = new int[1][edgesSegmentSize];
    }

    // Use ONLY within a writer lock area
    private void ensureEdgePointer(int pointer) {
        if (pointer + LEN_EDGE < edgesSegmentSize * getSegments())
            return;

        logger.info("Creating new edge segment " + edgesSegmentSize * 4f / (1 << 20) + " MB");
        edgeCurrentSegment++;
        int[][] tmp = new int[edgeCurrentSegment + 1][];
        for (int i = 0; i < edgesSegments.length; i++) {
            tmp[i] = edgesSegments[i];
        }
        tmp[edgeCurrentSegment] = new int[edgesSegmentSize];
        edgesSegments = tmp;
    }

    // Use ONLY within a writer lock area
    protected int ensureNodeIndex(int index) {
        if (index < size)
            return -1;

        size = index + 1;
        if (size <= lats.length)
            return -1;

        int cap = Math.max(10, Math.round(size * FACTOR));
        getDeletedNodes().ensureCapacity(cap);
        lats = Arrays.copyOf(lats, cap);
        lons = Arrays.copyOf(lons, cap);
        refToEdges = Arrays.copyOf(refToEdges, cap);
        return cap;
    }

    protected void initNodes(int cap) {
        lats = new float[cap];
        lons = new float[cap];
        // we ensure that edgePointer always starts from 1 => no need to fill with -1
        refToEdges = new int[cap];
        deletedNodes = new MyOpenBitSet(cap);
    }

    @Override
    public double getLatitude(int index) {
        // readLock.lock();
        return lats[index];
    }

    @Override
    public double getLongitude(int index) {
        // readLock.lock();       
        return lons[index];
    }

    @Override
    public void edge(int a, int b, double distance, boolean bothDirections) {
        edge(a, b, distance, CarStreetType.flagsDefault(bothDirections));
    }

    @Override
    public void edge(int a, int b, double distance, int flags) {
        // writeLock.lock();
        ensureNodeIndex(Math.max(a, b));
        internalEdgeAdd(a, b, distance, flags);
    }

    protected void saveToEdgeArea(int pointer, int data) {
        // uh, speed won't be improved via bit operations!
        int segNumber = pointer / edgesSegmentSize;
        int segPointer = pointer % edgesSegmentSize;
        edgesSegments[segNumber][segPointer] = data;
    }

    protected int getFromEdgeArea(int pointer) {
        // uh, speed won't be improved via bit operations!
        int segNumber = pointer / edgesSegmentSize;
        int segPointer = pointer % edgesSegmentSize;
        return edgesSegments[segNumber][segPointer];
    }

    private int getOtherNode(int nodeThis, int edgePointer) {
        int nodeA = getFromEdgeArea(edgePointer);
        if (nodeA == nodeThis)
            // return b
            return getFromEdgeArea(edgePointer + LEN_NODEA_ID);
        // return a
        return nodeA;
    }

    protected int getLinkPosInEdgeArea(int nodeThis, int nodeOther, int edgePointer) {
        if (nodeThis <= nodeOther)
            // get link to next a
            return edgePointer + LEN_NODEA_ID + LEN_NODEB_ID;

        // b
        return edgePointer + LEN_NODEA_ID + LEN_NODEB_ID + LEN_LINKA;
    }

    protected void internalEdgeAdd(int fromNodeId, int toNodeId, double dist, int flags) {
        int newOrExistingEdgePointer = nextEdgePointer();
        connectNewEdge(fromNodeId, newOrExistingEdgePointer);
        connectNewEdge(toNodeId, newOrExistingEdgePointer);
        writeEdge(newOrExistingEdgePointer, fromNodeId, toNodeId, EMPTY_LINK, EMPTY_LINK, flags, dist);
    }

    protected int nextEdgePointer() {
        edgeNextGlobalPointer += LEN_EDGE;
        if (edgeNextGlobalPointer < 0)
            throw new IllegalStateException("too many edges. new edge pointer would be negative.");
        return edgeNextGlobalPointer;
    }

    protected void connectNewEdge(int fromNodeId, int newOrExistingEdgePointer) {
        int edgePointer = refToEdges[fromNodeId];
        if (edgePointer > 0) {
            // append edge and overwrite EMPTY_LINK
            int lastEdgePointer = getLastEdgePointer(fromNodeId, edgePointer);
            saveToEdgeArea(lastEdgePointer, newOrExistingEdgePointer);
        } else {
            refToEdges[fromNodeId] = newOrExistingEdgePointer;
        }
    }

    // writes distance, flags, nodeThis, *nodeOther* and nextEdgePointer
    protected int writeEdge(int edgePointer, int nodeThis, int nodeOther,
            int nextEdgePointer, int nextEdgeOtherPointer, int flags, double dist) {
        ensureEdgePointer(edgePointer);

        if (nodeThis > nodeOther) {
            int tmp = nodeThis;
            nodeThis = nodeOther;
            nodeOther = tmp;

            tmp = nextEdgePointer;
            nextEdgePointer = nextEdgeOtherPointer;
            nextEdgeOtherPointer = tmp;

            flags = CarStreetType.swapDirection(flags);
        }

        writeA(edgePointer, nodeThis);
        writeB(edgePointer, nodeOther);
        edgePointer += LEN_NODEA_ID + LEN_NODEB_ID;

        saveToEdgeArea(edgePointer, nextEdgePointer);
        edgePointer += LEN_LINKA;

        saveToEdgeArea(edgePointer, nextEdgeOtherPointer);
        edgePointer += LEN_LINKB;

        saveToEdgeArea(edgePointer, flags);
        edgePointer += LEN_FLAGS;

        saveToEdgeArea(edgePointer, (int) (dist * DIST_UNIT));
        return edgePointer + LEN_DIST;
    }

    void writeA(int edgePointer, int node) {
        saveToEdgeArea(edgePointer, node);
    }

    void writeB(int edgePointer, int node) {
        saveToEdgeArea(edgePointer + LEN_NODEA_ID, node);
    }

    /**
     * @param edgeToUpdatePointer if it is negative then it will be saved to refToEdges
     */
    void internalEdgeRemove(int edgeToDeletePointer, int edgeToUpdatePointer, int node) {
        // an edge is shared across the two node even if the edge is not in both directions
        // so we need to know two edge-pointers pointing to the edge before edgeToDeletePointer
        int otherNode = getOtherNode(node, edgeToDeletePointer);
        int linkPos = getLinkPosInEdgeArea(node, otherNode, edgeToDeletePointer);
        int nextEdge = getFromEdgeArea(linkPos);
        if (edgeToUpdatePointer < 0)
            refToEdges[node] = nextEdge;
        else {
            int link = getLinkPosInEdgeArea(node, otherNode, edgeToUpdatePointer);
            saveToEdgeArea(link, nextEdge);
        }
    }

    private int getLastEdgePointer(int nodeThis, int edgePointer) {
        int lastLink = -1;
        int i = 0;
        int otherNode;
        for (; i < 1000; i++) {
            otherNode = getOtherNode(nodeThis, edgePointer);
            lastLink = getLinkPosInEdgeArea(nodeThis, otherNode, edgePointer);
            edgePointer = getFromEdgeArea(lastLink);
            if (edgePointer == EMPTY_LINK)
                break;
        }

        if (i >= 1000)
            throw new IllegalStateException("endless loop? edge count is probably not higher than " + i);
        return lastLink;
    }

    public void close() {
        flush();
    }

    @Override
    public EdgeIterator getEdges(int nodeId) {
        return new EdgeIterable(nodeId, true, true);
    }

    @Override
    public EdgeIterator getIncoming(int nodeId) {
        return new EdgeIterable(nodeId, true, false);
    }

    @Override
    public EdgeIterator getOutgoing(int nodeId) {
        return new EdgeIterable(nodeId, false, true);
    }

    protected class EdgeIterable implements EdgeIterator {

        int pointer;
        boolean in;
        boolean out;
        boolean foundNext;
        // edge properties        
        int flags;
        double distance;
        int nodeId;
        final int fromNode;
        int nextEdgePointer;

        public EdgeIterable(int node, boolean in, boolean out) {
            this.fromNode = node;
            this.nextEdgePointer = refToEdges[node];
            this.in = in;
            this.out = out;
        }

        void readNext() {
            // readLock.lock();
            pointer = nextEdgePointer;
            nodeId = getOtherNode(fromNode, pointer);
            if (fromNode != getOtherNode(nodeId, pointer))
                throw new IllegalStateException("requested node " + fromNode + " not stored in edge. "
                        + "was:" + nodeId + "," + getOtherNode(nodeId, pointer));

            // position to next edge
            nextEdgePointer = getFromEdgeArea(getLinkPosInEdgeArea(fromNode, nodeId, pointer));
            flags = getFlags(pointer);

            // switch direction flags if necessary
            if (fromNode > nodeId)
                flags = CarStreetType.swapDirection(flags);

            if (!in && !CarStreetType.isForward(flags) || !out && !CarStreetType.isBackward(flags)) {
                // skip this edge as it does not fit to defined filter
            } else {
                distance = getDist(pointer);
                foundNext = true;
            }
        }

        int edgePointer() {
            return pointer;
        }

        int nextEdgePointer() {
            return nextEdgePointer;
        }

        @Override public boolean next() {
            int i = 0;
            foundNext = false;
            for (; i < 1000; i++) {
                if (nextEdgePointer == EMPTY_LINK)
                    break;
                readNext();
                if (foundNext)
                    break;
            }
            if (i > 1000)
                throw new IllegalStateException("something went wrong: no end of edge-list found");
            return foundNext;
        }

        @Override public int node() {
            return nodeId;
        }

        @Override public double distance() {
            return distance;
        }

        @Override public int flags() {
            return flags;
        }

        @Override public int fromNode() {
            return fromNode;
        }
    }

    private float intToDist(int integ) {
        return integ / DIST_UNIT;
    }

    private int getFlags(int pointer) {
        return getFromEdgeArea(pointer + LEN_NODEA_ID + LEN_NODEB_ID + LEN_LINKA + LEN_LINKB);
    }

    private float getDist(int pointer) {
        return intToDist(getFromEdgeArea(pointer + LEN_NODEA_ID + LEN_NODEB_ID + LEN_LINKA + LEN_LINKB + LEN_FLAGS));
    }

    protected MemoryGraphSafe creatThis(String storage, int nodes, int edges) {
        return new MemoryGraphSafe(storage, nodes, edges);
    }

    @Override
    public Graph clone() {
        // readLock.lock();
        MemoryGraphSafe clonedGraph = creatThis(null, refToEdges.length, getMaxEdges());

        System.arraycopy(lats, 0, clonedGraph.lats, 0, lats.length);
        System.arraycopy(lons, 0, clonedGraph.lons, 0, lons.length);
        System.arraycopy(refToEdges, 0, clonedGraph.refToEdges, 0, refToEdges.length);

        clonedGraph.edgesSegments = new int[edgeCurrentSegment + 1][];
        for (int i = 0; i < clonedGraph.edgesSegments.length; i++) {
            clonedGraph.edgesSegments[i] = new int[edgesSegmentSize];
        }

        for (int i = 0; i < edgesSegments.length; i++) {
            System.arraycopy(edgesSegments[i], 0, clonedGraph.edgesSegments[i], 0, edgesSegmentSize);
        }
        clonedGraph.edgeCurrentSegment = edgeCurrentSegment;
        clonedGraph.edgesSegmentSize = edgesSegmentSize;
        clonedGraph.edgeNextGlobalPointer = edgeNextGlobalPointer;
        clonedGraph.size = size;
        clonedGraph.bounds = bounds;
        return clonedGraph;
    }

    private MyBitSet getDeletedNodes() {
        return deletedNodes;
    }

    @Override
    public void markNodeDeleted(int index) {
        // writeLock.lock();
        getDeletedNodes().add(index);
    }

    @Override
    public boolean isDeleted(int index) {
        // readLock.lock();
        return getDeletedNodes().contains(index);
    }

    /**
     * Saves this graph to disc
     */
    public void flush() {
        // we can avoid storing the deletedNodes bitset but we need to defragmentate before saving!
        // writeLock.lock();        
        optimize();
        save();
    }

    @Override
    public void optimize() {
        // writeLock.lock();
        int deleted = getDeletedNodes().getCardinality();
        if (deleted == 0)
            return;

        // bounds are not changed even if graph could get smaller!
//        if (deleted < size / 4) {
        inPlaceDelete(deleted);
//        } else
//            replacingDelete(deleted);
    }

    /**
     * This methods moves the last nodes into the deleted nodes, which is much more memory friendly
     * for only a few deletes but probably not for many deletes.
     */
    void inPlaceDelete(int deleted) {
        // Alternative to this method: use smaller segments for nodes and not one big fat java array?
        //
        // Prepare edge-update of nodes which are connected to deleted nodes        
        int toMoveNode = getNodes();
        int itemsToMove = 0;
        int maxMoves = Math.min(deleted, Math.max(0, toMoveNode - deleted));
        int newIndices[] = new int[maxMoves];
        int oldIndices[] = new int[maxMoves];

        final TIntIntHashMap oldToNewIndexMap = new TIntIntHashMap(deleted, 1.5f, -1, -1);
        MyBitSetImpl toUpdatedSet = new MyBitSetImpl(deleted * 3);
        for (int delNode = deletedNodes.next(0); delNode >= 0; delNode = deletedNodes.next(delNode + 1)) {
            EdgeIterator delEdgesIter = getEdges(delNode);
            while (delEdgesIter.next()) {
                int currNode = delEdgesIter.node();
                if (deletedNodes.contains(currNode))
                    continue;

                toUpdatedSet.add(currNode);
            }

            toMoveNode--;
            for (; toMoveNode >= 0; toMoveNode--) {
                if (!deletedNodes.contains(toMoveNode))
                    break;
            }

            if (toMoveNode < delNode)
                break;

            // create sorted old- to new-index map
            newIndices[itemsToMove] = delNode;
            oldIndices[itemsToMove] = toMoveNode;
            oldToNewIndexMap.put(toMoveNode, delNode);
            itemsToMove++;
        }

        // all deleted nodes could be connected to existing. remove the connections
        for (int toUpdateNode = toUpdatedSet.next(0); toUpdateNode >= 0; toUpdateNode = toUpdatedSet.next(toUpdateNode + 1)) {
            // remove all edges connected to the deleted nodes
            EdgeIterable nodesConnectedToDelIter = (EdgeIterable) getEdges(toUpdateNode);
            int prev = -1;
            while (nodesConnectedToDelIter.next()) {
                int nodeId = nodesConnectedToDelIter.node();
                if (deletedNodes.contains(nodeId))
                    internalEdgeRemove(nodesConnectedToDelIter.edgePointer(), prev, toUpdateNode);
                else
                    prev = nodesConnectedToDelIter.edgePointer();
            }
        }
        toUpdatedSet.clear();

        // marks connected nodes to rewrite the edges
        for (int i = 0; i < itemsToMove; i++) {
            int oldI = oldIndices[i];
            EdgeIterator movedEdgeIter = getEdges(oldI);
            while (movedEdgeIter.next()) {
                if (deletedNodes.contains(movedEdgeIter.node()))
                    throw new IllegalStateException("shouldn't happen the edge to the node " + movedEdgeIter.node() + " should be already deleted. " + oldI);

                toUpdatedSet.add(movedEdgeIter.node());
            }
        }

        // move nodes into deleted nodes
        for (int i = 0; i < itemsToMove; i++) {
            int oldI = oldIndices[i];
            int newI = newIndices[i];
            inPlaceDeleteNodeHook(oldI, newI);
        }

        // rewrite the edges of nodes connected to moved nodes
        // go through all edges and pick the necessary ... <- this is easier to implement then
        // a more efficient (?) breadth-first search
        int maxEdges = getMaxEdges() * LEN_EDGE;
        TIntHashSet hash = new TIntHashSet();
        for (int edgePointer = LEN_EDGE; edgePointer < maxEdges; edgePointer += LEN_EDGE) {
            // nodeId could be wrong - see tests            
            int nodeA = getFromEdgeArea(edgePointer);
            int nodeB = getFromEdgeArea(edgePointer + LEN_NODEA_ID);
            if (!toUpdatedSet.contains(nodeA) && !toUpdatedSet.contains(nodeB))
                continue;

            hash.add(nodeA);
            hash.add(nodeB);
            // now overwrite exiting edge with new node ids 
            // also flags and links could have changed due to different node order
            int updatedA = oldToNewIndexMap.get(nodeA);
            if (updatedA < 0)
                updatedA = nodeA;

            int updatedB = oldToNewIndexMap.get(nodeB);
            if (updatedB < 0)
                updatedB = nodeB;

            int linkA = getFromEdgeArea(getLinkPosInEdgeArea(nodeA, nodeB, edgePointer));
            int linkB = getFromEdgeArea(getLinkPosInEdgeArea(nodeB, nodeA, edgePointer));
            int flags = getFlags(edgePointer);
            float distance = getDist(edgePointer);
            writeEdge(edgePointer, updatedA, updatedB, linkA, linkB, flags, distance);
        }

        size -= deleted;
        deletedNodes = new MyOpenBitSet(size);
    }

    // Hint: edges with both directions will be returned only once!
    public EdgeIterator getAllEdges() {
        return new EdgeIterator() {
            private int edgePointer = 0;
            private int maxEdges = edgeNextGlobalPointer + LEN_EDGE;

            @Override
            public boolean next() {
                edgePointer += LEN_EDGE;
                return edgePointer < maxEdges;
            }

            @Override
            public int fromNode() {
                return getFromEdgeArea(edgePointer);
            }

            @Override
            public int node() {
                return getFromEdgeArea(edgePointer + LEN_NODEA_ID);
            }

            @Override
            public double distance() {
                return getDist(edgePointer);
            }

            @Override
            public int flags() {
                return getFlags(edgePointer);
            }
        };
    }

    protected void inPlaceDeleteNodeHook(int oldI, int newI) {
        refToEdges[newI] = refToEdges[oldI];
        lats[newI] = lats[oldI];
        lons[newI] = lons[oldI];
    }

    /**
     * This methods creates a new in-memory graph without the specified deleted nodes.
     */
    void replacingDelete(int deleted) {
        MemoryGraphSafe inMemGraph = new MemoryGraphSafe(null, getNodes() - deleted, getMaxEdges());

        // see MMapGraph for a near duplicate         
        int locs = this.getNodes();
        int newNodeId = 0;
        int[] old2NewMap = new int[locs];
        for (int oldNodeId = 0; oldNodeId < locs; oldNodeId++) {
            if (deletedNodes.contains(oldNodeId))
                continue;

            old2NewMap[oldNodeId] = newNodeId;
            newNodeId++;
        }

        newNodeId = 0;
        // create new graph with new mapped ids
        for (int oldNodeId = 0; oldNodeId < locs; oldNodeId++) {
            if (deletedNodes.contains(oldNodeId))
                continue;
            double lat = this.getLatitude(oldNodeId);
            double lon = this.getLongitude(oldNodeId);
            inMemGraph.setNode(newNodeId, lat, lon);
            EdgeIterator iter = this.getEdges(oldNodeId);
            while (iter.next()) {
                if (deletedNodes.contains(iter.node()))
                    continue;

                // TODO duplicate edges will be created!
                inMemGraph.internalEdgeAdd(newNodeId, old2NewMap[iter.node()], iter.distance(), iter.flags());
            }
            newNodeId++;
        }
        lats = inMemGraph.lats;
        lons = inMemGraph.lons;
        refToEdges = inMemGraph.refToEdges;
        for (int i = 0; i < edgesSegments.length; i++) {
            edgesSegments[i] = inMemGraph.edgesSegments[i];
        }

        size = inMemGraph.size;
        deletedNodes = new MyOpenBitSet(size);
    }

    public boolean save() {
        if (storageLocation == null)
            return false;
        // readLock.lock();
        try {
            File tmp = new File(storageLocation);
            if (!tmp.exists())
                tmp.mkdirs();

            Helper.writeFloats(storageLocation + "/lats", lats);
            Helper.writeFloats(storageLocation + "/lons", lons);
            Helper.writeInts(storageLocation + "/refs", refToEdges);
            for (int i = 0; i < edgesSegments.length; i++) {
                Helper.writeInts(storageLocation + "/edges" + i, edgesSegments[i]);
            }
            Helper.writeSettings(storageLocation + "/settings", size, creationTime, edgeNextGlobalPointer,
                    edgeCurrentSegment, edgesSegmentSize,
                    bounds.minLon, bounds.maxLon, bounds.minLat, bounds.maxLat);
            return true;
        } catch (IOException ex) {
            throw new RuntimeException("Couldn't write data to disc. location=" + storageLocation, ex);
        }
    }

    public boolean loadExisting(String storageDir) {
        if (storageDir == null || !new File(storageDir).exists())
            return false;

        //writeLock.lock();
        try {
            Object[] ob = Helper.readSettings(storageLocation + "/settings");
            if (ob.length < 3)
                throw new IllegalStateException("invalid file format");

            size = (Integer) ob[0];
            creationTime = (Long) ob[1];
            edgeNextGlobalPointer = (Integer) ob[2];
            edgeCurrentSegment = (Integer) ob[3];
            edgesSegmentSize = (Integer) ob[4];
            bounds = new BBox((Double) ob[5], (Double) ob[6], (Double) ob[7], (Double) ob[8]);
            logger.info("found graph " + storageLocation + " with nodes:" + size
                    + ", edges:" + edgeNextGlobalPointer / LEN_EDGE
                    + ", edges segments:" + (edgeCurrentSegment + 1)
                    + ", edges segmentSize:" + edgesSegmentSize
                    + ", created-at:" + new Date(creationTime));

            lats = Helper.readFloats(storageLocation + "/lats");
            lons = Helper.readFloats(storageLocation + "/lons");
            refToEdges = Helper.readInts(storageLocation + "/refs");
            edgesSegments = new int[edgeCurrentSegment + 1][];
            for (int i = 0; i <= edgeCurrentSegment; i++) {
                edgesSegments[i] = Helper.readInts(storageLocation + "/edges" + i);
            }
            if (size != lats.length)
                throw new IllegalStateException("incompatible size and latitude array length");
            deletedNodes = new MyOpenBitSet(size);
            return true;
        } catch (IOException ex) {
            throw new RuntimeException("Couldn't load data from disc. location=" + storageLocation, ex);
        }
    }

    public String getStorageLocation() {
        return storageLocation;
    }
}
