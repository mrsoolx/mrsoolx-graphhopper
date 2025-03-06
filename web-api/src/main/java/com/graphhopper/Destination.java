package com.graphhopper;

import com.graphhopper.util.shapes.GHPoint;

public class Destination {

    private String id;
    private GHPoint destinationPoint;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public GHPoint getDestinationPoint() {
        return destinationPoint;
    }

    public void setDestinationPoint(GHPoint destinationPoint) {
        this.destinationPoint = destinationPoint;
    }
}
