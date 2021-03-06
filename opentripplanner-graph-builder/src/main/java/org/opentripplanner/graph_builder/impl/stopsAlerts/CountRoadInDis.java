package org.opentripplanner.graph_builder.impl.stopsAlerts;

import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.operation.distance.DistanceOp;
import lombok.Setter;
import org.opentripplanner.common.geometry.DistanceLibrary;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.edgetype.StreetTraversalPermission;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.impl.StreetVertexIndexServiceImpl;
import org.opentripplanner.routing.services.StreetVertexIndexService;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Created with IntelliJ IDEA.
 * User: Ben
 * Date: 06/01/13
 * Time: 14:01
 * To change this template use File | Settings | File Templates.
 */
public class CountRoadInDis extends AbstractStopTester {

    Logger LOG = LoggerFactory.getLogger(CountRoadInDis.class);

    @Setter
    double distance; //distance in meters

    @Setter
    int numberOfStreets;

    @Setter
    StreetTraversalPermission allowedPermission;

    GeometryFactory geometryFactory = new GeometryFactory();

    @Override
    public boolean fulfillDemands(TransitStop ts, Graph graph) {
        if(graph.streetIndex == null){
            graph.streetIndex = new StreetVertexIndexServiceImpl(graph);
            LOG.debug("street index built.");
        }
        StreetVertexIndexService streetIndexService = graph.streetIndex;
        DistanceLibrary distanceLibrary;
        if(streetIndexService instanceof  StreetVertexIndexServiceImpl) {
            distanceLibrary = ((StreetVertexIndexServiceImpl)streetIndexService).getDistanceLibrary();
        }else{
            distanceLibrary = SphericalDistanceLibrary.getInstance();
        }
        Envelope env = new Envelope(ts.getCoordinate());
        double rInMeters;
        if(distanceLibrary instanceof  SphericalDistanceLibrary){
            rInMeters = ((SphericalDistanceLibrary)distanceLibrary).RADIUS_OF_EARTH_IN_M;
        }else{
            rInMeters = 6371.01 * 1000;
        }
        double degForOneMeter = (Math.PI/(180*rInMeters));
        double disInDeg = degForOneMeter * distance;
        env.expandBy(disInDeg);
        Collection<StreetEdge> streetEdges = streetIndexService.getEdgesForEnvelope(env);

        int counter = 0;
        for(StreetEdge streetEdge: streetEdges){
            //this is completely inaccurate!!! but may be good enough for now.
            double dis = DistanceOp.distance(streetEdge.getGeometry(),geometryFactory.createPoint(ts.getCoordinate()));
            double disMeters =  (dis*Math.PI/180) * rInMeters;
            if (disMeters <= distance && streetEdge.getPermission().allows(allowedPermission)){
                counter++;
            }
        }

        if(counter >= numberOfStreets)
            return true;
        return false;
    }
}
