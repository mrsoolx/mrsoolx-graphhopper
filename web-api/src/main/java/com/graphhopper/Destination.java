package com.graphhopper;

import com.graphhopper.util.shapes.GHPoint;

public class Destination {

    private Long id;
    private GHPoint destinationPoint;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public GHPoint getDestinationPoint() {
        return destinationPoint;
    }

    public void setDestinationPoint(GHPoint destinationPoint) {
        this.destinationPoint = destinationPoint;
    }
}
