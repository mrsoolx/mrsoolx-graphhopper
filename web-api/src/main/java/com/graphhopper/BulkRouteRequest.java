package com.graphhopper;

import com.graphhopper.util.shapes.GHPoint;

import java.util.List;

public class BulkRouteRequest {

    private GHPoint originPoint;
    private List<Destination> destinations;

    public GHPoint getOriginPoint() {
        return originPoint;
    }

    public void setOriginPoint(GHPoint originPoint) {
        this.originPoint = originPoint;
    }

    public List<Destination> getDestinations() {
        return destinations;
    }

    public void setDestinations(List<Destination> destinations) {
        this.destinations = destinations;
    }
}
