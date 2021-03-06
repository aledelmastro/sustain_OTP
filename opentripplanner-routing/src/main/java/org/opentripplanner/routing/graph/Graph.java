/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.routing.graph;

import static org.opentripplanner.common.IterableLibrary.filter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.Iterables;
import org.onebusaway.gtfs.impl.calendar.CalendarServiceImpl;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.calendar.CalendarServiceData;
import org.onebusaway.gtfs.model.calendar.ServiceDate;
import org.onebusaway.gtfs.services.calendar.CalendarService;
import org.opentripplanner.common.MavenVersion;
import org.opentripplanner.gbannotation.GraphBuilderAnnotation;
import org.opentripplanner.gbannotation.NoFutureDates;
import org.opentripplanner.model.GraphBundle;
import org.opentripplanner.routing.core.MortonVertexComparatorFactory;
import org.opentripplanner.routing.core.TransferTable;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.TimetableSnapshotSource;
import org.opentripplanner.routing.impl.DefaultStreetVertexIndexFactory;
import org.opentripplanner.routing.services.StreetVertexIndexFactory;
import org.opentripplanner.routing.services.StreetVertexIndexService;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Lists;
import com.google.common.collect.Multiset;
import com.vividsolutions.jts.geom.Envelope;

/**
 * A graph is really just one or more indexes into a set of vertexes. It used to keep edgelists for each vertex, but those are in the vertex now.
 */
public class Graph implements Serializable {

    private static final long serialVersionUID = MavenVersion.VERSION.getUID();

    private final MavenVersion mavenVersion = MavenVersion.VERSION;

    private static final Logger LOG = LoggerFactory.getLogger(Graph.class);

    // transit feed validity information in seconds since epoch
    private long transitServiceStarts = Long.MAX_VALUE;

    private long transitServiceEnds = 0;

    private Map<Class<?>, Object> _services = new HashMap<Class<?>, Object>();

    private TransferTable transferTable = new TransferTable();

    private GraphBundle bundle;

    /* vertex index by name is reconstructed from edges */
    private transient Map<String, Vertex> vertices;
    
    private transient CalendarService calendarService;

    private boolean debugData = true;

    private transient Map<Integer, Vertex> vertexById;

    private transient Map<Integer, Edge> edgeById;

    public transient StreetVertexIndexService streetIndex;

    public transient TimetableSnapshotSource timetableSnapshotSource = null;

    private transient List<GraphBuilderAnnotation> graphBuilderAnnotations = new LinkedList<GraphBuilderAnnotation>(); // initialize for tests

    private Collection<String> agenciesIds = new HashSet<String>();

    private Collection<Agency> agencies = new HashSet<Agency>();

    private transient Set<Edge> temporaryEdges;

    private VertexComparatorFactory vertexComparatorFactory = new MortonVertexComparatorFactory();

    private transient TimeZone timeZone = null;

    private List<Stop> stops;

    public Graph(Graph basedOn) {
        this();
        this.bundle = basedOn.getBundle();
    }

    public Graph() {
        this.vertices = new ConcurrentHashMap<String, Vertex>();
        this.temporaryEdges = Collections.newSetFromMap(new ConcurrentHashMap<Edge, Boolean>());
        this.edgeById = new ConcurrentHashMap<Integer, Edge>();
        this.vertexById = new ConcurrentHashMap<Integer, Vertex>();
    }

    public List<Stop> getAllStops() {
        /* Lazy-initialize stops list. */
        if (stops == null) {
            stops = Lists.newArrayList();
            for (TransitStop stopv : Iterables.filter(vertices.values(), TransitStop.class)) {
                stops.add(stopv.getStop());
            }
        }
        return stops;
    }

    /**
     * Add the given vertex to the graph. Ideally, only vertices should add themselves to the graph, when they are constructed or deserialized.
     * 
     * @param v the vertex to add
     */
    public void addVertex(Vertex v) {
        Vertex old = vertices.put(v.getLabel(), v);
        if (old != null) {
            if (old == v)
                LOG.error("repeatedly added the same vertex: {}", v);
            else
                LOG.error("duplicate vertex label in graph (added vertex to graph anyway): {}", v);
        }
    }

    /**
     * Removes a vertex from the graph.
     * 
     * Called from streetutils, must be public for now
     * 
     * @param v
     */
    public void removeVertex(Vertex v) {
        if (vertices.remove(v.getLabel()) != v) {
            LOG.error(
                    "attempting to remove vertex that is not in graph (or mapping value was null): {}",
                    v);
        }        
    }

    /* convenient in tests and such, but avoid using in general */
    @Deprecated
    public Vertex getVertex(String label) {
        return vertices.get(label);
    }
    
    /**
     * Returns the vertex with the given ID or null if none is present.
     * 
     * NOTE: you may need to run rebuildVertexAndEdgeIndices() for the indices
     * to be accurate.
     * 
     * @param id
     * @return
     */
    public Vertex getVertexById(int id) {
        return this.vertexById.get(id);
    }

    /**
     * Get all the vertices in the graph.
     * @return
     */
    public Collection<Vertex> getVertices() {
        return this.vertices.values();
    }
    
    /**
     * Returns the edge with the given ID or null if none is present.
     * 
     * NOTE: you may need to run rebuildVertexAndEdgeIndices() for the indices
     * to be accurate.
     * 
     * @param id
     * @return
     */
    public Edge getEdgeById(int id) {
        return edgeById.get(id);
    }
    
    /**
     * Return all the edges in the graph.
     * @return
     */
    public Collection<Edge> getEdges() {
        Set<Edge> edges = new HashSet<Edge>();
        for (Vertex v : this.getVertices()) {
            edges.addAll(v.getOutgoing());
        }
        return edges;
    }

    /**
     * Return only the StreetEdges in the graph.
     * @return
     */
    public Collection<StreetEdge> getStreetEdges() {
        Collection<Edge> allEdges = this.getEdges();
        return Lists.newArrayList(filter(allEdges, StreetEdge.class));
    }    
    
    public boolean containsVertex(Vertex v) {
        return (v != null) && vertices.get(v.getLabel()) == v;
    }

    @SuppressWarnings("unchecked")
    public <T> T putService(Class<T> serviceType, T service) {
        return (T) _services.put(serviceType, service);
    }

    public boolean hasService(Class<?> serviceType) {
        return _services.containsKey(serviceType);
    }

    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceType) {
        return (T) _services.get(serviceType);
    }

    public void remove(Vertex vertex) {
        vertices.remove(vertex.getLabel());
    }

    public void removeVertexAndEdges(Vertex vertex) {
        if (!containsVertex(vertex)) {
            throw new IllegalStateException("attempting to remove vertex that is not in graph.");
        }
        for (Edge e : vertex.getIncoming()) {
            temporaryEdges.remove(e);
        }
        for (Edge e : vertex.getOutgoing()) {
            temporaryEdges.remove(e);
        }
        vertex.removeAllEdges();
        this.remove(vertex);
    }

    public Envelope getExtent() {
        Envelope env = new Envelope();
        for (Vertex v : getVertices()) {
            env.expandToInclude(v.getCoordinate());
        }
        return env;
    }

    public TransferTable getTransferTable() {
        return transferTable;
    }

    // Infer the time period covered by the transit feed
    public void updateTransitFeedValidity(CalendarServiceData data) {
        long now = new Date().getTime() / 1000;
        final long SEC_IN_DAY = 24 * 60 * 60;
        HashSet<String> agenciesWithFutureDates = new HashSet<String>();
        HashSet<String> agencies = new HashSet<String>();
        for (AgencyAndId sid : data.getServiceIds()) {
            agencies.add(sid.getAgencyId());
            for (ServiceDate sd : data.getServiceDatesForServiceId(sid)) {
                long t = sd.getAsDate().getTime() / 1000;
                if (t > now) {
                    agenciesWithFutureDates.add(sid.getAgencyId());
                }
                // assume feed is unreliable after midnight on last service day
                long u = t + SEC_IN_DAY;
                if (t < this.transitServiceStarts)
                    this.transitServiceStarts = t;
                if (u > this.transitServiceEnds)
                    this.transitServiceEnds = u;
            }
        }
        for (String agency : agencies) {
            if (!agenciesWithFutureDates.contains(agency)) {
                LOG.warn(this.addBuilderAnnotation(new NoFutureDates(agency)));
            }
        }
    }

    // Check to see if we have transit information for a given date
    public boolean transitFeedCovers(long t) {
        return t >= this.transitServiceStarts && t < this.transitServiceEnds;
    }

    public GraphBundle getBundle() {
        return bundle;
    }

    public void setBundle(GraphBundle bundle) {
        this.bundle = bundle;
    }

    public int countVertices() {
        return vertices.size();
    }

    /**
     * Find the total number of edges in this Graph. There are assumed to be no Edges in an incoming edge list that are not in an outgoing edge list.
     * 
     * @return number of outgoing edges in the graph
     */
    public int countEdges() {
        int ne = 0;
        for (Vertex v : getVertices()) {
            ne += v.getDegreeOut();
        }
        return ne;
    }

    /**
     * Add a collection of edges from the edgesById index.
     * @param es
     */
    private void addEdgesToIndex(Collection<Edge> es) {
        for (Edge e : es) {
            this.edgeById.put(e.getId(), e);
        }
    }
    
    /**
     * Rebuilds any indices on the basis of current vertex and edge IDs.
     * 
     * If you want the index to be accurate, you must run this every time the 
     * vertex or edge set changes.
     * 
     * TODO(flamholz): keep the indices up to date with changes to the graph.
     * This is not simple because the Vertex constructor may add itself to the graph
     * before the Vertex has any edges, so updating indices on addVertex is insufficient.
     */
    public void rebuildVertexAndEdgeIndices() {
        this.vertexById = new HashMap<Integer, Vertex>(AbstractVertex.getMaxIndex());
        Collection<Vertex> vertices = getVertices();
        for (Vertex v : vertices) {
            vertexById.put(v.getIndex(), v);
        }

        // Create map from edge ids to edges.
        this.edgeById = new HashMap<Integer, Edge>();
        for (Vertex v : vertices) {
            // TODO(flamholz): this check seems superfluous.
            if (v == null) {
                continue;
            }

            // Assumes that all the edges appear in at least one outgoing edge list.
            addEdgesToIndex(v.getOutgoing());
        }
    }

    private void readObject(ObjectInputStream inputStream) throws ClassNotFoundException,
            IOException {
        inputStream.defaultReadObject();

        temporaryEdges = Collections.newSetFromMap(new ConcurrentHashMap<Edge, Boolean>());
    }

    /**
     * Add a graph builder annotation to this graph's list of graph builder annotations. The return value of this method is the annotation's message,
     * which allows for a single-line idiom that creates, registers, and logs a new graph builder annotation:
     * log.warning(graph.addBuilderAnnotation(new SomeKindOfAnnotation(param1, param2)));
     * 
     * If the graphBuilderAnnotations field of this graph is null, the annotation is not actually saved, but the message is still returned. This
     * allows annotation registration to be turned off, saving memory and disk space when the user is not interested in annotations.
     */
    public String addBuilderAnnotation(GraphBuilderAnnotation gba) {
        String ret = gba.getMessage();
        if (this.graphBuilderAnnotations != null)
            this.graphBuilderAnnotations.add(gba);
        return ret;
    }

    public List<GraphBuilderAnnotation> getBuilderAnnotations() {
        return this.graphBuilderAnnotations;
    }

    /* (de) serialization */

    public enum LoadLevel {
        BASIC, FULL, DEBUG;
    }

    public static Graph load(File file, LoadLevel level) throws IOException, ClassNotFoundException {
        LOG.info("Reading graph " + file.getAbsolutePath() + " ...");
        // cannot use getClassLoader() in static context
        ObjectInputStream in = new ObjectInputStream(new FileInputStream(file));
        return load(in, level);
    }

    public static Graph load(ClassLoader classLoader, File file, LoadLevel level)
            throws IOException, ClassNotFoundException {
        LOG.info("Reading graph " + file.getAbsolutePath() + " with alternate classloader ...");
        ObjectInputStream in = new GraphObjectInputStream(new BufferedInputStream(
                new FileInputStream(file)), classLoader);
        return load(in, level);
    }

    public static Graph load(InputStream is, LoadLevel level) throws ClassNotFoundException,
            IOException {
        return load(new ObjectInputStream(is), level);
    }

    /**
     * Default load. Uses DefaultStreetVertexIndexFactory.
     * @param in
     * @param level
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     */
    public static Graph load(ObjectInputStream in, LoadLevel level) throws IOException,
            ClassNotFoundException {
        return load(in, level, new DefaultStreetVertexIndexFactory());
    }
    
    /**
     * Loading which allows you to specify StreetVertexIndexFactory and inject other implementation.
     * @param in
     * @param level
     * @param indexFactory
     * @return
     * @throws IOException
     * @throws ClassNotFoundException
     */
    @SuppressWarnings("unchecked")
    public static Graph load(ObjectInputStream in, LoadLevel level,
            StreetVertexIndexFactory indexFactory) throws IOException, ClassNotFoundException {
        try {
            Graph graph = (Graph) in.readObject();
            LOG.debug("Basic graph info read.");
            if (graph.graphVersionMismatch())
                throw new RuntimeException("Graph version mismatch detected.");
            if (level == LoadLevel.BASIC)
                return graph;
            // vertex edge lists are transient to avoid excessive recursion depth
            // vertex list is transient because it can be reconstructed from edges
            LOG.debug("Loading edges...");
            List<Edge> edges = (ArrayList<Edge>) in.readObject();
            graph.vertices = new HashMap<String, Vertex>();
            
            for (Edge e : edges) {
                graph.vertices.put(e.getFromVertex().getLabel(), e.getFromVertex());
                graph.vertices.put(e.getToVertex().getLabel(), e.getToVertex());
            }
            // trim edge lists to length
            for (Vertex v : graph.getVertices())
                v.compact();
            LOG.info("Main graph read. |V|={} |E|={}", graph.countVertices(), graph.countEdges());
            
            graph.streetIndex = indexFactory.newIndex(graph);
            LOG.debug("street index built.");
            
            LOG.debug("Rebuilding edge and vertex indices");
            graph.rebuildVertexAndEdgeIndices();
            
            if (level == LoadLevel.FULL) {
                return graph;
            }
            
            if (graph.debugData) {
                graph.graphBuilderAnnotations = (List<GraphBuilderAnnotation>) in.readObject();
                LOG.debug("Debug info read.");
            } else {
                LOG.warn("Graph file does not contain debug data.");
            }
            return graph;
        } catch (InvalidClassException ex) {
            LOG.error("Stored graph is incompatible with this version of OTP, please rebuild it.");
            throw new IllegalStateException("Stored Graph version error", ex);
        }
    }

    /**
     * Compares the OTP version number stored in the graph with that of the currently running instance. Logs warnings explaining that mismatched
     * versions can cause problems.
     * 
     * @return false if Maven versions match (even if commit ids do not match), true if Maven version of graph does not match this version of OTP or
     *         graphs are otherwise obviously incompatible.
     */
    private boolean graphVersionMismatch() {
        MavenVersion v = MavenVersion.VERSION;
        MavenVersion gv = this.mavenVersion;
        LOG.info("Graph version: {}", gv);
        LOG.info("OTP version:   {}", v);
        if (!v.equals(gv)) {
            LOG.error("This graph was built with a different version of OTP. Please rebuild it.");
            return true; // do not allow graph use
        } else if (!v.commit.equals(gv.commit)) {
            if (v.qualifier.equals("SNAPSHOT")) {
                LOG.warn("This graph was built with the same SNAPSHOT version of OTP, but a "
                        + "different commit. Please rebuild the graph if you experience incorrect "
                        + "behavior. ");
                return false; // graph might still work
            } else {
                LOG.error("Commit mismatch in non-SNAPSHOT version. This implies a problem with "
                        + "the build or release process.");
                return true; // major problem
            }
        } else {
            // no version mismatch, no commit mismatch
            LOG.info("This graph was built with the currently running version and commit of OTP.");
            return false;
        }
    }

    public void save(File file) throws IOException {
        LOG.info("Main graph size: |V|={} |E|={}", this.countVertices(), this.countEdges());
        LOG.info("Writing graph " + file.getAbsolutePath() + " ...");
        ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(
                new FileOutputStream(file)));
        try {
            save(out);
            out.close();
        } catch (RuntimeException e) {
            out.close();
            file.delete(); // remove half-written file
            throw e;
        }
    }

    public void save(ObjectOutputStream out) throws IOException {
        LOG.debug("Consolidating edges...");
        // this is not space efficient
        List<Edge> edges = new ArrayList<Edge>(this.countEdges());
        for (Vertex v : getVertices()) {
            // there are assumed to be no edges in an incoming list that are not
            // in an outgoing list
            edges.addAll(v.getOutgoing());
            if (v.getDegreeOut() + v.getDegreeIn() == 0)
                LOG.debug("vertex {} has no edges, it will not survive serialization.", v);
        }
        LOG.debug("Assigning vertex/edge ID numbers...");
        this.rebuildVertexAndEdgeIndices();
        LOG.debug("Writing edges...");
        out.writeObject(this);
        out.writeObject(edges);
        if (debugData) {
            // should we make debug info generation conditional?
            LOG.debug("Writing debug data...");
            out.writeObject(this.graphBuilderAnnotations);
            out.writeObject(this.vertexById);
            out.writeObject(this.edgeById);
        } else {
            LOG.debug("Skipping debug data.");
        }
        LOG.info("Graph written.");
    }

    /* deserialization for org.opentripplanner.customize */
    private static class GraphObjectInputStream extends ObjectInputStream {
        ClassLoader classLoader;

        public GraphObjectInputStream(InputStream in, ClassLoader classLoader) throws IOException {
            super(in);
            this.classLoader = classLoader;
        }

        @Override
        public Class<?> resolveClass(ObjectStreamClass osc) {
            try {
                return Class.forName(osc.getName(), false, classLoader);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Integer getIdForEdge(Edge edge) {
        return edge.getId();
    }

    public CalendarService getCalendarService() {
        if (calendarService == null) {
            CalendarServiceData data = this.getService(CalendarServiceData.class);
            if (data != null) {
                CalendarServiceImpl calendarService = new CalendarServiceImpl();
                calendarService.setData(data);
                this.calendarService = calendarService;
            }
        }
        return this.calendarService;
    }

    public int removeEdgelessVertices() {
        int removed = 0;
        List<Vertex> toRemove = new LinkedList<Vertex>();
        for (Vertex v : this.getVertices())
            if (v.getDegreeOut() + v.getDegreeIn() == 0)
                toRemove.add(v);
        // avoid concurrent vertex map modification
        for (Vertex v : toRemove) {
            this.remove(v);
            removed += 1;
            LOG.trace("removed edgeless vertex {}", v);
        }
        return removed;
    }

    public Collection<String> getAgencyIds() {
        return agenciesIds;
    }

    public Collection<Agency> getAgencies() {
        return agencies;
    }

    public void addAgency(Agency agency) {
        agencies.add(agency);
        agenciesIds.add(agency.getId());
    }

    public void addTemporaryEdge(Edge edge) {
        temporaryEdges.add(edge);
    }

    public void removeTemporaryEdge(Edge edge) {
        if (edge.getFromVertex() == null || edge.getToVertex() == null) {
            return;
        }
        temporaryEdges.remove(edge);
    }

    public Collection<Edge> getTemporaryEdges() {
        return temporaryEdges;
    }

    public VertexComparatorFactory getVertexComparatorFactory() {
        return vertexComparatorFactory;
    }

    public void setVertexComparatorFactory(VertexComparatorFactory vertexComparatorFactory) {
        this.vertexComparatorFactory = vertexComparatorFactory;
    }

    /**
     * Returns the time zone for the first agency in this graph. This is used to interpret times in API requests. The JVM default time zone cannot be
     * used because we support multiple graphs on one server via the routerId. Ideally we would want to interpret times in the time zone of the
     * geographic location where the origin/destination vertex or board/alight event is located. This may become necessary when we start making graphs
     * with long distance train, boat, or air services.
     */
    public TimeZone getTimeZone() {
        if (timeZone == null) {
            Collection<String> agencyIds = this.getAgencyIds();
            if (agencyIds.size() == 0) {
                timeZone = TimeZone.getTimeZone("GMT");
                LOG.warn("graph contains no agencies; API request times will be interpreted as GMT.");
            } else {
                CalendarService cs = this.getCalendarService();
                for (String agencyId : agencyIds) {
                    TimeZone tz = cs.getTimeZoneForAgencyId(agencyId);
                    if (timeZone == null) {
                        LOG.debug("graph time zone set to {}", tz);
                        timeZone = tz;
                    } else if (!timeZone.equals(tz)) {
                        LOG.error("agency time zone differs from graph time zone: {}", tz);
                    }
                }
            }
        }
        return timeZone;
    }

    public void summarizeBuilderAnnotations() {
        List<GraphBuilderAnnotation> gbas = this.graphBuilderAnnotations;
        Multiset<Class<? extends GraphBuilderAnnotation>> classes = HashMultiset.create();
        LOG.info("Summary (number of each type of annotation):");
        for (GraphBuilderAnnotation gba : gbas)
            classes.add(gba.getClass());
        for (Multiset.Entry<Class<? extends GraphBuilderAnnotation>> e : classes.entrySet()) {
            String name = e.getElement().getSimpleName();
            int count = e.getCount();
            LOG.info("    {} - {}", name, count);
        }
    }

}